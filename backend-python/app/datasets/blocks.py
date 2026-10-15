"""
sheet 内数据块识别（设计文档 5.1）。
1. XY 切分：以全空行 / 全空列为边界递归切分出矩形块（合并单元格已填充，算作一体）；
2. 表头切分：块内部出现"新表头行"（两表之间没有空行）时从该行切开；
3. 分类：data 数据块 / note 备注文字 / title 标题（作为下方数据块的表名）/ ignore 噪声。
只在样本行（前 SAMPLE_KEEP 行）上识别；触到样本底部的块向下延伸到 sheet 最后一个非空行。
"""
from . import infer

NOTE_WORDS = ("说明", "备注", "口径", "注：", "注:", "注意", "来源", "定义")


def _occ(sample, width):
    return [[(c < len(r) and not infer.is_empty(r[c])) for c in range(width)] for r in sample]


def _trim(occ, r1, c1, r2, c2):
    rows = [r for r in range(r1, r2 + 1) if any(occ[r][c1:c2 + 1])]
    if not rows:
        return None
    cols = [c for c in range(c1, c2 + 1) if any(occ[r][c] for r in rows)]
    return rows[0], cols[0], rows[-1], cols[-1]


def _segments(flags):
    """[True/False...] → 连续 True 的 (start, end) 列表"""
    out, start = [], None
    for i, f in enumerate(flags):
        if f and start is None:
            start = i
        if not f and start is not None:
            out.append((start, i - 1))
            start = None
    if start is not None:
        out.append((start, len(flags) - 1))
    return out


def xy_cut(occ, rng, depth=0):
    t = _trim(occ, *rng)
    if t is None:
        return []
    r1, c1, r2, c2 = t
    if depth > 12:
        return [t]
    row_segs = _segments([any(occ[r][c1:c2 + 1]) for r in range(r1, r2 + 1)])
    if len(row_segs) > 1:
        return [b for a, z in row_segs for b in xy_cut(occ, (r1 + a, c1, r1 + z, c2), depth + 1)]
    col_segs = _segments([any(occ[r][c] for r in range(r1, r2 + 1)) for c in range(c1, c2 + 1)])
    if len(col_segs) > 1:
        return [b for a, z in col_segs for b in xy_cut(occ, (r1, c1 + a, r2, c1 + z), depth + 1)]
    return [t]


def _is_valueish(v):
    """数字或日期（数据行特征）"""
    if infer.is_empty(v):
        return False
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return True
    return infer.parse_number(v)[0] is not None or infer.parse_date(v)[0] is not None


def _headerish(row, need):
    filled = [v for v in row if not infer.is_empty(v)]
    if len(filled) < need or len({str(v).strip() for v in filled}) < 2:
        return False
    return sum(infer.is_text_like(v) for v in filled) / len(filled) >= 0.8


def split_by_header(sample, occ, blk):
    """块内部出现新表头时切开：该行几乎全是文本，上一行和下一行都含数字/日期，且该行在上一行是数字的列上是文本"""
    r1, c1, r2, c2 = blk
    width = c2 - c1 + 1
    seg = lambda r: (sample[r] + [None] * (c2 + 1 - len(sample[r])))[c1:c2 + 1]
    max_filled = max(sum(occ[r][c1:c2 + 1]) for r in range(r1, min(r2, r1 + 30) + 1))
    need = max(2, -(-max_filled // 2))
    first_header = None
    for r in range(r1, min(r2, r1 + infer.HEADER_SCAN_ROWS) + 1):
        if _headerish(seg(r), need):
            first_header = r
            break
    if first_header is None:
        return [blk]
    for r in range(first_header + 3, r2):
        row, prev, nxt = seg(r), seg(r - 1), seg(r + 1)
        if not _headerish(row, need) or not any(_is_valueish(v) for v in prev) or not any(_is_valueish(v) for v in nxt):
            continue
        if not any(infer.is_text_like(row[i]) and _is_valueish(prev[i]) for i in range(width)):
            continue
        top, bottom = _trim(occ, r1, c1, r - 1, c2), _trim(occ, r, c1, r2, c2)
        return [top] + split_by_header(sample, occ, bottom)
    return [blk]


def classify(sample, blk):
    r1, c1, r2, c2 = blk
    cells = [(r, c, sample[r][c]) for r in range(r1, r2 + 1) for c in range(c1, min(c2 + 1, len(sample[r])))
             if not infer.is_empty(sample[r][c])]
    texts = [str(v).strip() for _, _, v in cells]
    per_row = {}
    for r, _, v in cells:
        per_row.setdefault(r, set()).add(str(v).strip())
    single_rows = sum(1 for s in per_row.values() if len(s) == 1)
    if not cells:
        return "ignore"
    numeric = sum(1 for _, _, v in cells if _is_valueish(v))
    if single_rows >= 0.8 * len(per_row) and numeric <= 0.2 * len(cells):
        if len(per_row) == 1 and len(texts[0]) <= 40 and not any(w in texts[0] for w in NOTE_WORDS):
            return "title"
        return "note" if sum(len(t) for t in set(texts)) >= 8 else "ignore"
    if (c2 - c1 + 1) >= 2 and (r2 - r1 + 1) >= 3:
        return "data"
    return "ignore"


def note_text(sample, blk, limit=1000):
    r1, c1, r2, c2 = blk
    lines = []
    for r in range(r1, r2 + 1):
        vals = []
        for c in range(c1, min(c2 + 1, len(sample[r]))):
            v = sample[r][c]
            s = "" if infer.is_empty(v) else str(v).strip()
            if s and s not in vals:
                vals.append(s)
        if vals:
            lines.append(" ".join(vals))
    return "\n".join(lines)[:limit]


def detect(sample, last_row, maxc, truncated):
    """返回 [{"range": (r1,c1,r2,c2), "kind": ..., "title": 上方标题或 None, "note": 文本}]，按位置排序"""
    width = maxc + 1
    occ = _occ(sample, width)
    raw = xy_cut(occ, (0, 0, len(sample) - 1, maxc))
    pieces = []
    for b in raw:
        pieces.extend(split_by_header(sample, occ, b) if (b[2] - b[0]) >= 5 else [b])
    out = []
    for b in sorted(pieces):
        kind = classify(sample, b)
        r1, c1, r2, c2 = b
        if kind == "data" and truncated and r2 == len(sample) - 1:
            r2 = last_row
        out.append({"range": (r1, c1, r2, c2), "kind": kind,
                    "note": note_text(sample, b) if kind in ("note", "title") else None, "title": None})
    # 标题：紧邻数据块上方（3 行内）、列有重叠的 title 块作为表名
    for t in [x for x in out if x["kind"] == "title"]:
        tr1, tc1, tr2, tc2 = t["range"]
        cands = [d for d in out if d["kind"] == "data" and 0 < d["range"][0] - tr2 <= 3
                 and not (d["range"][3] < tc1 or d["range"][1] > tc2) and d["title"] is None]
        if cands:
            cands[0]["title"] = t["note"]
    return out
