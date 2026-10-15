// Excel 区域工具：列号 ↔ 字母、"A5:H120" ↔ [r1, c1, r2, c2]（0 基、含两端）
export const letter = c => { let s = ''; c += 1; while (c) { const r = (c - 1) % 26; s = String.fromCharCode(65 + r) + s; c = Math.floor((c - 1) / 26) } return s }
const colIndex = l => [...l.toUpperCase()].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1
export function parseRange(ref) {
  const m = /^\s*([A-Za-z]{1,3})(\d+)\s*:\s*([A-Za-z]{1,3})(\d+)\s*$/.exec(ref || '')
  if (!m) return null
  const a = [+m[2] - 1, colIndex(m[1]), +m[4] - 1, colIndex(m[3])]
  return [Math.min(a[0], a[2]), Math.min(a[1], a[3]), Math.max(a[0], a[2]), Math.max(a[1], a[3])]
}
export const formatRange = (r1, c1, r2, c2) => `${letter(c1)}${r1 + 1}:${letter(c2)}${r2 + 1}`
/** 两个区域的外接矩形（合并相邻块用） */
export function unionRange(a, b) {
  const x = parseRange(a), y = parseRange(b)
  return formatRange(Math.min(x[0], y[0]), Math.min(x[1], y[1]), Math.max(x[2], y[2]), Math.max(x[3], y[3]))
}
