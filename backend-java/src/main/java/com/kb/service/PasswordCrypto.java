package com.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码传输加密：浏览器用 RSA-OAEP(SHA-256) 加密 {password, nonce, ts}，这里解密并校验。
 * <ul>
 *   <li>RSA-2048 密钥对在启动时生成，私钥只在内存里，不落盘、不打印；重启后自动换新钥；</li>
 *   <li>nonce 由 {@link #issue()} 下发，60 秒有效、只能用一次（取出即删除），防止重放；</li>
 *   <li>ts 是浏览器时间（毫秒），与服务器相差超过 kb.auth.ts-skew-seconds 即拒绝。</li>
 * </ul>
 * 注意：这是对 HTTPS 的补充（防止明文密码出现在抓包、代理日志、浏览器调试工具中），不能替代 HTTPS。
 */
@Service
public class PasswordCrypto {

    /** 解密或校验失败：message 为给用户看的中文提示 */
    public static class BadPayload extends RuntimeException {
        public BadPayload(String msg) { super(msg); }
    }

    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    /** 同时存在的 nonce 上限，防止被刷接口耗尽内存 */
    private static final int MAX_NONCES = 20000;

    private final KeyPair keys;
    private final String publicKeyB64;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    private final long nonceTtlMs;
    private final long skewMs;

    public PasswordCrypto(@Value("${kb.auth.nonce-ttl-seconds:60}") long nonceTtlSeconds,
                          @Value("${kb.auth.ts-skew-seconds:300}") long skewSeconds) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, random);
        this.keys = g.generateKeyPair();
        this.publicKeyB64 = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()); // X.509 SPKI DER
        this.nonceTtlMs = nonceTtlSeconds * 1000;
        this.skewMs = skewSeconds * 1000;
    }

    /** 下发公钥和一次性 nonce */
    public Map<String, Object> issue() {
        long now = System.currentTimeMillis();
        if (nonces.size() > MAX_NONCES / 2) {
            nonces.values().removeIf(exp -> exp < now);
        }
        if (nonces.size() >= MAX_NONCES) {
            throw new BadPayload("请求过于频繁，请稍后再试");
        }
        byte[] b = new byte[18];
        random.nextBytes(b);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        nonces.put(nonce, now + nonceTtlMs);
        return Map.of("publicKey", publicKeyB64, "nonce", nonce, "expiresIn", nonceTtlMs / 1000, "alg", "RSA-OAEP-256");
    }

    /**
     * 解密 passwordCipher（base64），校验 nonce 与时间戳后返回明文密码（只在内存中用于 BCrypt 校验 / 加密）。
     */
    public String decrypt(Object cipherB64) {
        if (!(cipherB64 instanceof String s) || s.isBlank()) {
            throw new BadPayload("缺少加密的密码，请刷新页面后重试");
        }
        JsonNode p;
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
            c.init(Cipher.DECRYPT_MODE, keys.getPrivate(), OAEP);
            p = json.readTree(new String(c.doFinal(Base64.getDecoder().decode(s.trim())), StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 不记录异常细节，避免泄露任何密文 / 明文信息
            throw new BadPayload("密码解密失败，请刷新页面后重试");
        }
        String nonce = p.path("nonce").asText("");
        Long exp = nonce.isEmpty() ? null : nonces.remove(nonce);   // 取出即作废：同一个 nonce 只能用一次
        long now = System.currentTimeMillis();
        if (exp == null) {
            throw new BadPayload("登录凭据已使用或无效，请重试");
        }
        if (exp < now) {
            throw new BadPayload("登录凭据已过期，请重试");
        }
        long ts = p.path("ts").asLong(0);
        if (Math.abs(now - ts) > skewMs) {
            throw new BadPayload("设备时间与服务器相差过大，请校准时间后重试");
        }
        JsonNode pw = p.get("password");
        if (pw == null || !pw.isTextual()) {
            throw new BadPayload("密码格式错误");
        }
        return pw.asText();
    }
}
