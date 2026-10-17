/**
 * 密码传输加密：先 GET /api/auth/public-key 取公钥和一次性 nonce，
 * 再用 RSA-OAEP(SHA-256) 加密 {password, nonce, ts}，请求里只带密文 passwordCipher。
 *
 * 优先用浏览器自带的 WebCrypto（crypto.subtle）；但 crypto.subtle 只在"安全上下文"
 * （https 或 localhost）可用，手机用 http://192.168.x.x 访问开发环境时没有它，
 * 这时自动切换到下面的纯 JS 实现（BigInt 模幂 + SHA-256 + OAEP 填充，按 RFC 8017），
 * 输出与 WebCrypto 完全一致的密文，后端无需区分。随机数用 crypto.getRandomValues（非安全上下文也可用）。
 */
import api from './api'

const te = new TextEncoder()
const b64ToBytes = s => Uint8Array.from(atob(s), c => c.charCodeAt(0))
function bytesToB64(b) {
  let s = ''
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000))
  return btoa(s)
}

/** 加密一个密码，返回 base64 密文；每次调用都会取新的 nonce（一次性） */
export async function encryptPassword(password) {
  const { publicKey, nonce } = await api.get('/auth/public-key')
  const msg = te.encode(JSON.stringify({ password, nonce, ts: Date.now() }))
  if (msg.length > 190) throw new Error('密码过长')
  const der = b64ToBytes(publicKey)
  if (globalThis.crypto?.subtle && !globalThis.__forceRsaFallback) {
    const key = await crypto.subtle.importKey('spki', der, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt'])
    return bytesToB64(new Uint8Array(await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, key, msg)))
  }
  return bytesToB64(oaepEncrypt(der, msg))
}

// ---------------- 纯 JS 兜底实现 ----------------

/** SHA-256（FIPS 180-4） */
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2])
export function sha256(data) {
  const l = data.length, n = ((l + 9 + 63) >> 6) << 6
  const m = new Uint8Array(n); m.set(data); m[l] = 0x80
  const dv = new DataView(m.buffer)
  dv.setUint32(n - 4, l * 8 >>> 0); dv.setUint32(n - 8, Math.floor(l / 0x20000000))
  const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19])
  const W = new Uint32Array(64)
  const r = (x, s) => (x >>> s) | (x << (32 - s))
  for (let o = 0; o < n; o += 64) {
    for (let i = 0; i < 16; i++) W[i] = dv.getUint32(o + i * 4)
    for (let i = 16; i < 64; i++) {
      const s0 = r(W[i - 15], 7) ^ r(W[i - 15], 18) ^ (W[i - 15] >>> 3)
      const s1 = r(W[i - 2], 17) ^ r(W[i - 2], 19) ^ (W[i - 2] >>> 10)
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0
    }
    let [a, b, c, d, e, f, g, h] = H
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (r(e, 6) ^ r(e, 11) ^ r(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + W[i]) | 0
      const t2 = ((r(a, 2) ^ r(a, 13) ^ r(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0
    }
    H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += h
  }
  const out = new Uint8Array(32), ov = new DataView(out.buffer)
  H.forEach((v, i) => ov.setUint32(i * 4, v))
  return out
}

/** 从 SPKI DER 中取出 RSA 模数 n 和指数 e */
function parseSpki(der) {
  let p = 0
  const read = () => {
    const tag = der[p++]; let len = der[p++]
    if (len & 0x80) { const k = len & 0x7f; len = 0; for (let i = 0; i < k; i++) len = len * 256 + der[p++] }
    return { tag, len, start: p }
  }
  const outer = read()                    // SEQUENCE
  if (outer.tag !== 0x30) throw new Error('公钥格式错误')
  const alg = read(); p = alg.start + alg.len   // AlgorithmIdentifier
  const bits = read()                     // BIT STRING
  if (bits.tag !== 0x03) throw new Error('公钥格式错误')
  p++                                     // 未用位数 0
  read()                                  // RSAPublicKey SEQUENCE
  const int = () => { const t = read(); const v = der.subarray(t.start, t.start + t.len); p = t.start + t.len; return v }
  const toBig = b => BigInt('0x' + (Array.from(b, x => x.toString(16).padStart(2, '0')).join('') || '0'))
  const nb = int(), eb = int()
  return { n: toBig(nb), e: toBig(eb), k: nb[0] === 0 ? nb.length - 1 : nb.length }
}

function mgf1(seed, len) {
  const out = new Uint8Array(len)
  for (let c = 0, o = 0; o < len; c++) {
    const buf = new Uint8Array(seed.length + 4); buf.set(seed)
    new DataView(buf.buffer).setUint32(seed.length, c)
    const h = sha256(buf); out.set(h.subarray(0, Math.min(32, len - o)), o); o += 32
  }
  return out
}

function modPow(b, e, m) {
  let r = 1n; b %= m
  while (e > 0n) { if (e & 1n) r = r * b % m; b = b * b % m; e >>= 1n }
  return r
}

/** RSAES-OAEP-ENCRYPT（SHA-256 / MGF1-SHA-256，空 label） */
export function oaepEncrypt(spkiDer, msg) {
  const { n, e, k } = parseSpki(spkiDer)
  const hLen = 32
  if (msg.length > k - 2 * hLen - 2) throw new Error('密码过长')
  const db = new Uint8Array(k - hLen - 1)
  db.set(sha256(new Uint8Array(0)))
  db[db.length - msg.length - 1] = 1
  db.set(msg, db.length - msg.length)
  const seed = crypto.getRandomValues(new Uint8Array(hLen))
  const dbMask = mgf1(seed, db.length)
  for (let i = 0; i < db.length; i++) db[i] ^= dbMask[i]
  const seedMask = mgf1(db, hLen)
  for (let i = 0; i < hLen; i++) seed[i] ^= seedMask[i]
  const em = new Uint8Array(k); em.set(seed, 1); em.set(db, 1 + hLen)
  const m = BigInt('0x' + Array.from(em, x => x.toString(16).padStart(2, '0')).join(''))
  const hex = modPow(m, e, n).toString(16).padStart(k * 2, '0')
  const out = new Uint8Array(k)
  for (let i = 0; i < k; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16)
  return out
}
