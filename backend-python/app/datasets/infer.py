"""
单元格值解析、表头识别、类型推断。
所有函数都是纯函数，便于单元测试。
"""
import datetime as dt
import re

SAMPLE_ROWS = 5000          # 类型推断抽样行数
TYPE_THRESHOLD = 0.9        # 90% 以上非空值能解析成该类型才定型
HEADER_SCAN_ROWS = 20

TOTAL_WORDS = {"合计", "总计", "小计", "总和", "汇总", "共计", "total", "subtotal", "grand total", "sum"}
DATE_HINT = re.compile(r"日期|时间|日|月份|date|time|day", re.I)

# Excel 序列号日期的合理范围：1954-10-18 ~ 2064-04-22
SERIAL_MIN, SERIAL_MAX = 20000, 60000
_EPOCH = dt.datetime(1899, 12, 30)


def is_empty(v) -> bool:
    return v is None or (isinstance(v, str) and v.strip() == "")


# ---------- 数字 ----------

_NUM_STRIP = re.compile(r"[\s,，¥￥$€£]|元|人民币|RMB", re.I)


def parse_number(v):
    """返回 (数值, 是否百分比)；无法解析返回 (None, False)"""
    if isinstance(v, bool):
        return None, False
    if isinstance(v, (int, float)):
        return v, False
    if not isinstance(v, str):
        return None, False
    s = _NUM_STRIP.sub("", v.strip())
    if not s:
        return None, False
    neg = False
    if s.startswith("(") and s.endswith(")") or s.startswith("（") and s.endswith("）"):
        neg, s = True, s[1:-1]
    pct = s.endswith("%")
    if pct:
        s = s[:-1]
    mult = 1
    if s.endswith("万"):
        mult, s = 10000, s[:-1]
    elif s.endswith("亿"):
        mult, s = 100000000, s[:-1]
    try:
        n = float(s)
    except ValueError:
        return None, False
    if pct:
        n /= 100
    n *= mult
    if neg:
        n = -n
    if not pct and n.is_integer() and abs(n) < 9e15 and "." not in s:
        n = int(n)
    return n, pct


# ---------- 日期 ----------

_DATE_PATTERNS = [
    (re.compile(r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$"), "ymd"),
    (re.compile(r"^(\d{4})年(\d{1,2})月(\d{1,2})[日号]?(?:\s*(\d{1,2}):(\d{2})(?::(\d{2}))?)?$"), "ymd"),
    (re.compile(r"^(\d{4})(\d{2})(\d{2})$"), "ymd8"),
    (re.compile(r"^(\d{1,2})月(\d{1,2})[日号]?$"), "md"),
    (re.compile(r"^(\d{4})[-/.年](\d{1,2})月?$"), "ym"),
]


def parse_date(v, year_hint=None, date_hint=False):
    """
    返回 (datetime 或 None, 是否缺年份)。
    date_hint=True（列名像日期）时，才把纯数字当作 Excel 序列号或 20260901 解析，避免把金额误判成日期。
    """
    if isinstance(v, dt.datetime):
        return v, False
    if isinstance(v, dt.date):
        return dt.datetime(v.year, v.month, v.day), False
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        if not date_hint:
            return None, False
        if SERIAL_MIN <= v <= SERIAL_MAX:
            return _EPOCH + dt.timedelta(days=float(v)), False
        if isinstance(v, int) or float(v).is_integer():
            v = str(int(v))
        else:
            return None, False
    if not isinstance(v, str):
        return None, False
    s = v.strip()
    for pat, kind in _DATE_PATTERNS:
        m = pat.match(s)
        if not m:
            continue
        try:
            g = m.groups()
            if kind == "ymd":
                return dt.datetime(int(g[0]), int(g[1]), int(g[2]), int(g[3] or 0), int(g[4] or 0), int(g[5] or 0)), False
            if kind == "ymd8":
                if not date_hint:
                    return None, False
                return dt.datetime(int(g[0]), int(g[1]), int(g[2])), False
            if kind == "md":
                return dt.datetime(year_hint or dt.date.today().year, int(g[0]), int(g[1])), True
            if kind == "ym":
                return dt.datetime(int(g[0]), int(g[1]), 1), False
        except ValueError:
            return None, False
    return None, False


def is_text_like(v) -> bool:
    """表头特征：字符串且不是数字、不是日期"""
    if not isinstance(v, str) or not v.strip():
        return False
    if parse_number(v)[0] is not None:
        return False
    return parse_date(v)[0] is None


def is_total_row(row) -> bool:
    """前两个非空单元格中有"合计/总计/小计"等字样"""
    seen = 0
    for v in row:
        if is_empty(v):
            continue
        s = str(v).strip().replace(" ", "").lower()
        if s in TOTAL_WORDS or (len(s) <= 6 and any(s.endswith(w) for w in ("合计", "总计", "小计"))):
            return True
        seen += 1
        if seen >= 2:
            break
    return False


# ---------- 表头识别 ----------

def detect_header(rows: list, width: int):
    """
    rows：块内前若干行（已填充合并单元格）。返回表头所在行的相对下标列表（0/1/2 行），
    规则：从上往下找第一个"非空单元格 ≥ 一半列数、文本占比 ≥ 70%"的行；
    若它的下一行同样像表头，并且本行有被横向合并填充的重复值（分组表头），则两行合并为表头。
    找不到时返回 [] 表示无表头（列名用"列1、列2…"）。
    """
    scan = rows[:HEADER_SCAN_ROWS]
    max_filled = max((sum(not is_empty(v) for v in r) for r in scan), default=0)
    if max_filled == 0:
        return []

    def headerish(r):
        filled = [v for v in r if not is_empty(v)]
        need = max(2, -(-max_filled // 2)) if max_filled >= 2 else 1
        if len(filled) < need:
            return False
        # 横向合并的标题行填充后所有值相同，不算表头
        if max_filled >= 2 and len({str(v).strip() for v in filled}) < 2:
            return False
        return sum(is_text_like(v) for v in filled) / len(filled) >= 0.7

    for i, r in enumerate(scan):
        if headerish(r):
            nxt = scan[i + 1] if i + 1 < len(scan) else None
            if nxt is not None and headerish(nxt):
                filled = [str(v) for v in r if not is_empty(v)]
                if len(set(filled)) < len(filled):     # 有横向合并填充出的重复分组名
                    return [i, i + 1]
            return [i]
    return []


def build_column_names(header_rows: list, width: int) -> list:
    names = []
    for c in range(width):
        parts = []
        for r in header_rows:
            v = r[c] if c < len(r) else None
            s = "" if is_empty(v) else re.sub(r"\s+", "", str(v))
            if s and (not parts or parts[-1] != s):
                parts.append(s)
        names.append("_".join(parts) or f"列{c + 1}")
    seen = {}
    out = []
    for n in names:
        n = n[:60]
        if n in seen:
            seen[n] += 1
            n = f"{n}_{seen[n]}"
        else:
            seen[n] = 1
        out.append(n)
    return out


# ---------- 类型推断 ----------

def infer_type(name: str, values: list, year_hint=None) -> dict:
    """返回 {"type": DATE/TIMESTAMP/BIGINT/DOUBLE/VARCHAR, "is_percent": bool, "missing_year": bool, "bad": n}"""
    vals = [v for v in values if not is_empty(v)]
    if not vals:
        return {"type": "VARCHAR", "is_percent": False, "missing_year": False, "bad": 0}
    hint = bool(DATE_HINT.search(name or ""))
    n = len(vals)

    dates = [parse_date(v, year_hint, hint) for v in vals]
    ok = [d for d, _ in dates if d is not None]
    # 纯数字列（无日期提示）不会被当成日期；有日期提示且大部分可解析时才是日期
    if len(ok) >= TYPE_THRESHOLD * n and (hint or any(isinstance(v, (str, dt.date)) for v in vals)):
        has_time = any(d.hour or d.minute or d.second for d in ok)
        return {"type": "TIMESTAMP" if has_time else "DATE", "is_percent": False,
                "missing_year": any(m for _, m in dates), "bad": n - len(ok)}

    nums = [parse_number(v) for v in vals]
    okn = [x for x, _ in nums if x is not None]
    if len(okn) >= TYPE_THRESHOLD * n:
        pct = sum(p for _, p in nums) >= 0.5 * len(okn)
        is_int = not pct and all(isinstance(x, int) for x in okn)
        return {"type": "BIGINT" if is_int else "DOUBLE", "is_percent": pct, "missing_year": False, "bad": n - len(okn)}
    return {"type": "VARCHAR", "is_percent": False, "missing_year": False, "bad": 0}


def convert(v, typ: str, year_hint=None, date_hint=False):
    """按目标类型转换单个值，失败返回 None（统计为异常值）"""
    if is_empty(v):
        return None
    if typ in ("DATE", "TIMESTAMP"):
        d, _ = parse_date(v, year_hint, True)
        if d is None:
            return None
        return d.date().isoformat() if typ == "DATE" else d.isoformat(sep=" ")
    if typ in ("BIGINT", "DOUBLE"):
        x, _ = parse_number(v)
        if x is None:
            return None
        if typ == "BIGINT":
            return int(x) if float(x).is_integer() else None
        return float(x)
    if isinstance(v, dt.datetime):
        return v.isoformat(sep=" ")
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v).strip()


def year_hint_from(rows) -> int | None:
    """文件中出现最多的年份（给"9月1日"这种缺年份的日期用）"""
    cnt = {}
    for r in rows[:2000]:
        for v in r:
            y = None
            if isinstance(v, (dt.date, dt.datetime)):
                y = v.year
            elif isinstance(v, str):
                m = re.match(r"^\s*(20\d{2}|19\d{2})[-/.年]", v)
                y = int(m.group(1)) if m else None
            if y:
                cnt[y] = cnt.get(y, 0) + 1
    return max(cnt, key=cnt.get) if cnt else None
