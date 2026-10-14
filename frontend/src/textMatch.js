// 文本规范化与匹配工具：用于在 PDF 页面中找到引用片段所在的位置并高亮。
// 后端入库时对文本做了 NFKC 规范化并修正了部首字符，这里做同样处理，两边才能比对上。

// 与后端 document_loader._RADICAL_MAP 保持一致：CJK 部首补充区字符 → 标准汉字
const RADICAL_MAP = {
  '⻔': '门', '⻓': '长', '⻛': '风', '⻅': '见', '⺠': '民', '⻋': '车', '⻉': '贝',
  '⻜': '飞', '⻝': '食', '⻬': '齐', '⻢': '马', '⻚': '页', '⻰': '龙', '⻥': '鱼',
  '⻦': '鸟', '⻩': '黄', '⻨': '麦', '⻮': '齿', '⻆': '角', '⻘': '青', '⻙': '韦',
  '⻄': '西', '⻣': '骨', '⻤': '鬼', '⻧': '卤', '⻭': '齿',
}

/** 规范化：NFKC + 部首修正 + 去掉空白和 Markdown 表格符号，只保留“有意义”的字符 */
export function normalizeText(s) {
  return (s || '')
    .normalize('NFKC')
    .replace(/[\u2E80-\u2EFF]/g, ch => RADICAL_MAP[ch] || ch)
    .replace(/[\s|\-:]+/g, '')
}

/**
 * 在 PDF 页面的文字中找出属于引用片段的部分。
 * pdf.js 把页面文字拆成很多小段（items），段的边界和入库时的文本不一定一致，
 * 所以先把整页文字规范化后拼成一个长字符串，再用引用片段中每 10 个字一组的“窗口”去查找，
 * 被窗口覆盖到的字符记为命中；一段文字有一半以上字符命中就高亮。
 * 这样既能容忍换行、空格、表格符号的差异，又不会把“5.5”这类零散短词误判为引用内容。
 *
 * @param items  pdf.js getTextContent() 返回的 items
 * @param chunk  引用片段原文
 * @returns      需要高亮的 item 下标数组
 */
export function matchItems(items, chunk) {
  const WINDOW = 10
  const STEP = 3
  const target = normalizeText(chunk)
  let page = ''
  const owner = []                  // owner[i] = 第 i 个字符属于哪个 item
  items.forEach((it, idx) => {
    const t = normalizeText(it.str)
    page += t
    for (let k = 0; k < t.length; k++) owner.push(idx)
  })
  const hit = new Uint8Array(page.length)
  const size = Math.min(WINDOW, target.length)
  for (let i = 0; size > 0 && i + size <= target.length; i += STEP) {
    const piece = target.slice(i, i + size)
    let from = page.indexOf(piece)
    while (from >= 0) {
      hit.fill(1, from, from + size)
      from = page.indexOf(piece, from + 1)
    }
  }
  // 统计每个 item 的命中比例
  const total = new Map()
  const got = new Map()
  owner.forEach((idx, i) => {
    total.set(idx, (total.get(idx) || 0) + 1)
    if (hit[i]) got.set(idx, (got.get(idx) || 0) + 1)
  })
  return [...total.keys()].filter(idx => (got.get(idx) || 0) / total.get(idx) >= 0.5)
}

/** 生成会话 ID：优先用浏览器自带的 randomUUID（仅 HTTPS / localhost 可用），否则用随机数拼接 */
export function newSessionId() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  return 's-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}
