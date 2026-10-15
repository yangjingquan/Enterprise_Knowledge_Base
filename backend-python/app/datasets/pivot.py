"""
透视表 / 交叉表识别与逆透视（设计文档 5.2）。
宽表：行=地区，列=1月…12月，单元格=金额 → 长表 (地区, 月份, 金额)；
两级表头（"1月_消耗"、"1月_成交"）→ (地区, 月份, 消耗, 成交)。
"""
import datetime as dt
import re

from . import infer

_CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "1": 1, "2": 2, "3": 3, "4": 4}


def parse_period(name: str, year_hint=None):
    """列名 → (kind, 值)。kind: month（DATE 月初）/ date / quarter（VARCHAR 2026Q1）/ year（BIGINT）"""
    s = re.sub(r"\s+", "", str(name or ""))
    y = year_hint or dt.date.today().year
    m = re.fullmatch(r"(?:(\d{4})[年\-/.])?(\d{1,2})月(?:份)?", s) or re.fullmatch(r"(\d{4})[\-/.](\d{1,2})", s)
    if m:
        yy, mm = int(m.group(1) or y), int(m.group(2))
        if 1 <= mm <= 12:
            return "month", dt.date(yy, mm, 1).isoformat()
    m = re.fullmatch(r"(?:(\d{4})年?)?(?:Q([1-4])|第?([一二三四1-4])季度?)", s, re.I)
    if m:
        q = int(m.group(2)) if m.group(2) else _CN_NUM[m.group(3)]
        return "quarter", f"{m.group(1) or y}Q{q}"
    m = re.fullmatch(r"(\d{4})年?", s)
    if m and 1990 <= int(m.group(1)) <= 2100:
        return "year", int(m.group(1))
    d, _ = infer.parse_date(s, y)
    if d is not None:
        return "date", d.date().isoformat()
    return None, None


VAR_TYPES = {"month": "DATE", "date": "DATE", "quarter": "VARCHAR", "year": "BIGINT"}
VAR_NAMES = {"month": "月份", "date": "日期", "quarter": "季度", "year": "年份"}


def _is_total_name(n):
    s = str(n).replace(" ", "")
    return any(w in s for w in ("合计", "总计", "小计", "汇总")) or s.lower() in ("total", "sum")


def detect(cols: list, year_hint=None):
    """
    cols：analyze_block 产出的列（name/type/col）。返回透视建议或 None：
    {"id_cols": [col...], "items": [{"col", "var", "metric"}], "metrics": [...], "var_name", "var_type",
     "value_name", "value_type", "drop_cols": [col...]}
    """
    ids = []
    for c in cols:
        if c["type"] in ("VARCHAR",) and not parse_period(c["name"], year_hint)[0]:
            ids.append(c)
        else:
            break
    if not ids or len(ids) > 3:
        return None
    rest = [c for c in cols[len(ids):]]
    drop = [c for c in rest if _is_total_name(c["name"])]
    vals = [c for c in rest if c not in drop]
    if len(vals) < 3 or any(c["type"] not in ("BIGINT", "DOUBLE") for c in vals):
        return None

    def try_items(get):
        items, kinds = [], set()
        for c in vals:
            var_part, metric = get(c["name"])
            if var_part is None:
                return None
            k, v = parse_period(var_part, year_hint)
            if k is None:
                return None
            kinds.add(k)
            items.append({"col": c["col"], "var": v, "metric": metric})
        return (items, kinds.pop()) if len(kinds) == 1 else None

    one = try_items(lambda n: (n, None))
    two_a = try_items(lambda n: (n.rsplit("_", 1)[0], n.rsplit("_", 1)[1]) if "_" in n else (None, None))
    two_b = try_items(lambda n: (n.split("_", 1)[1], n.split("_", 1)[0]) if "_" in n else (None, None))
    found = one or two_a or two_b
    if not found:
        # 两级表头且上级是年份："2026年_1月"
        found = try_items(lambda n: (n.replace("_", ""), None) if "_" in n else (None, None))
    if not found:
        return None
    items, kind = found
    metrics = []
    for it in items:
        if it["metric"] is not None and it["metric"] not in metrics:
            metrics.append(it["metric"])
    if metrics:
        # 每个周期都要有全部指标，否则不按两级处理
        periods = {}
        for it in items:
            periods.setdefault(it["var"], set()).add(it["metric"])
        if any(len(v) != len(metrics) for v in periods.values()) or len(periods) < 2:
            return None
    elif len(items) < 3:
        return None
    vtype = "BIGINT" if all(c["type"] == "BIGINT" for c in vals) else "DOUBLE"
    return {"id_cols": [c["col"] for c in ids], "items": items, "metrics": metrics,
            "var_name": VAR_NAMES[kind], "var_type": VAR_TYPES[kind], "value_name": "数值",
            "value_type": vtype, "drop_cols": [c["col"] for c in drop]}


def out_columns(p: dict, cols: list) -> list:
    """逆透视后的列定义：[{name, type, is_percent}]"""
    by = {c["col"]: c for c in cols}
    out = [{"name": by[c]["name"], "type": by[c]["type"], "is_percent": False, "col": c} for c in p["id_cols"]]
    out.append({"name": p["var_name"], "type": p["var_type"], "is_percent": False, "col": None})
    names = p.get("metric_names") or {}
    for m in (p["metrics"] or [None]):
        out.append({"name": names.get(m or "", m) if m else p["value_name"], "type": p["value_type"],
                    "is_percent": False, "col": None})
    return out


def transform_row(p: dict, seg: list, c1: int, year_hint=None):
    """一行宽表 → 若干行长表（已转换类型；某周期全部指标为空时跳过）"""
    ids = [infer.convert(seg[c - c1], "VARCHAR") for c in p["id_cols"]]
    order, vals = [], {}
    for it in p["items"]:
        if it["var"] not in vals:
            order.append(it["var"])
            vals[it["var"]] = {}
        vals[it["var"]][it["metric"]] = infer.convert(seg[it["col"] - c1], p["value_type"], year_hint)
    metrics = p["metrics"] or [None]
    for v in order:
        row = [vals[v].get(m) for m in metrics]
        if all(x is None for x in row):
            continue
        yield ids + [v] + row


def transform_meta(p: dict, rng: str) -> dict:
    from .reader import col_letter
    return {"type": "unpivot", "id_cols": [col_letter(c) for c in p["id_cols"]], "var_name": p["var_name"],
            "value_name": p["value_name"], "metrics": p["metrics"], "source_range": rng}


METRIC_HINTS = ["销售额", "成交金额", "成交额", "交易额", "营业额", "GMV", "收入", "营收", "利润", "毛利", "成本", "费用",
                "消耗", "花费", "投放金额", "金额", "订单数", "订单量", "订单", "销量", "销售量", "数量", "件数",
                "点击量", "点击", "曝光量", "曝光", "访客数", "UV", "PV", "人数", "客户数", "用户数", "转化率", "预算", "目标"]


def guess_value_name(*texts) -> str | None:
    """从块标题 / 表名里找指标词（"2026年各地区月度销售额（元）" → "销售额"），找不到返回 None"""
    for t in texts:
        if not t:
            continue
        s = str(t)
        hits = [(s.find(w), -len(w), w) for w in METRIC_HINTS if w.lower() in s.lower()]
        if hits:
            # 最长匹配优先，其次靠后的（"月度销售额"中的"销售额"）
            hits.sort(key=lambda x: (x[1], -x[0]))
            return hits[0][2]
    return None
