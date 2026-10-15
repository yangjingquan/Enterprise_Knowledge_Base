"""数据集导入（问数第一期）：单元测试 + 样例文件端到端（pandas 标准答案比对）"""
import datetime as dt
import shutil
from pathlib import Path

import pytest

from app.datasets import infer, store
from app.datasets.reader import DatasetError, parse_range, format_range
from tools.gen_sample_datasets import generate


# ---------- 单元测试 ----------

@pytest.mark.parametrize("raw,val,pct", [
    ("¥1,234.50", 1234.5, False), ("1.2万", 12000.0, False), ("3.5%", 0.035, True),
    ("(500)", -500, False), ("12,000", 12000, False), (88, 88, False), ("abc", None, False), ("", None, False),
])
def test_parse_number(raw, val, pct):
    x, p = infer.parse_number(raw)
    assert (x == pytest.approx(val) if val is not None else x is None) and p == pct


@pytest.mark.parametrize("raw,hint,exp", [
    ("2026/9/1", False, dt.datetime(2026, 9, 1)), ("2026-09-02", False, dt.datetime(2026, 9, 2)),
    ("2026年9月3日", False, dt.datetime(2026, 9, 3)), ("20260904", True, dt.datetime(2026, 9, 4)),
    ("20260904", False, None), (46266, True, dt.datetime(2026, 9, 1)), (46266, False, None),
    ("2026-09-01 08:30", False, dt.datetime(2026, 9, 1, 8, 30)), ("hello", True, None),
])
def test_parse_date(raw, hint, exp):
    assert infer.parse_date(raw, None, hint)[0] == exp


def test_md_date_uses_year_hint():
    d, missing = infer.parse_date("9月5日", 2025)
    assert d == dt.datetime(2025, 9, 5) and missing


def test_range():
    assert parse_range("A5:H120") == (4, 0, 119, 7)
    assert format_range(*parse_range("b2:AA10")) == "B2:AA10"
    with pytest.raises(DatasetError):
        parse_range("5A:H")


def test_header_skips_title_and_merges_group_header():
    rows = [["季度报表"] * 4, ["来源：平台", None, None, None],
            ["基础", "基础", "数据", "数据"], ["日期", "渠道", "消耗", "订单"], ["2026-09-01", "抖音", 1, 2]]
    h = infer.detect_header(rows, 4)
    assert h == [2, 3]
    assert infer.build_column_names([rows[i] for i in h], 4) == ["基础_日期", "基础_渠道", "数据_消耗", "数据_订单"]


def test_header_single_and_dup_names():
    rows = [["名称", "金额", "金额"], ["a", 1, 2]]
    assert infer.detect_header(rows, 3) == [0]
    assert infer.build_column_names([rows[0]], 3) == ["名称", "金额", "金额_2"]


def test_infer_types():
    assert infer.infer_type("日期", ["2026/9/1", 46267, dt.datetime(2026, 9, 3)])["type"] == "DATE"
    assert infer.infer_type("金额", [1, 2, 46000])["type"] == "BIGINT"      # 无日期提示的数字不当日期
    assert infer.infer_type("金额", ["¥1.5", 2])["type"] == "DOUBLE"
    t = infer.infer_type("转化率", ["3%", "4.5%"])
    assert t["type"] == "DOUBLE" and t["is_percent"]
    assert infer.infer_type("渠道", ["抖音", "1", "小红书"])["type"] == "VARCHAR"
    assert infer.infer_type("时间", ["2026-09-01 08:00"])["type"] == "TIMESTAMP"


def test_total_row():
    assert infer.is_total_row(["合计", None, 100])
    assert infer.is_total_row([None, "总计", 1])
    assert not infer.is_total_row(["华东", "合计金额说明很长很长", 1])


# ---------- 端到端 ----------

@pytest.fixture(scope="module")
def env(tmp_path_factory):
    root = tmp_path_factory.mktemp("ds")
    up, dsd = root / "uploads", root / "datasets"
    truth = generate(up)
    old = store.UPLOAD_DIR, store.DATASET_DIR
    store.UPLOAD_DIR, store.DATASET_DIR = up, dsd
    yield up, truth
    store.UPLOAD_DIR, store.DATASET_DIR = old


def _import(did, path, ext, title=""):
    plan = store.analyze(did, str(path), ext, title)
    meta = store.ingest(did, str(path), ext, plan)
    return plan, meta


def test_xlsx_end_to_end(env):
    up, truth = env
    plan, meta = _import(1, up / "marketing-2026Q3.xlsx", "xlsx")
    sheets = {s["name"]: s for s in plan["sheets"]}
    assert sheets["隐藏备份"].get("skipped") and not sheets["隐藏备份"]["blocks"]
    b = sheets["投放日报"]["blocks"][0]
    assert b["header_rows"] == [3, 4] and b["totals_found"] == 1
    names = [c["name"] for c in b["columns"]]
    assert names[0] == "基础信息_日期" and names[4] == "投放数据_消耗(元)"
    types = [c["type"] for c in b["columns"]]
    assert types == ["DATE", "VARCHAR", "VARCHAR", "VARCHAR", "DOUBLE", "BIGINT", "BIGINT", "BIGINT", "DOUBLE"]
    assert meta["table_count"] == 2
    t1 = meta["tables"][0]
    df = truth["daily"]
    assert t1["row_count"] == len(df) and t1["totals_dropped"] == 1
    assert all(c["bad"] == 0 for c in t1["columns"])
    con = store.connect(1)
    try:
        # 9月1日–15日各渠道成交金额
        got = dict(con.execute("SELECT c2, round(sum(c9),2) FROM t_1 WHERE c1 BETWEEN '2026-09-01' AND '2026-09-15' "
                               "GROUP BY 1").fetchall())
        m = df[(df.日期 >= dt.date(2026, 9, 1)) & (df.日期 <= dt.date(2026, 9, 15))].groupby("渠道").成交金额.sum().round(2)
        assert got.keys() == set(m.index) and all(abs(got[k] - m[k]) < 0.01 for k in m.index)
        # 每日消耗、日期范围
        assert con.execute("SELECT min(c1), max(c1), count(DISTINCT c1) FROM t_1").fetchone() == \
               (dt.date(2026, 7, 1), dt.date(2026, 9, 30), 92)
        assert abs(con.execute("SELECT sum(c5) FROM t_1").fetchone()[0] - df.消耗.sum()) < 0.01
        assert con.execute("SELECT sum(c8) FROM t_1").fetchone()[0] == df.成交订单.sum()
        tg = truth["targets"]
        assert con.execute("SELECT sum(c3) FROM t_2").fetchone()[0] == tg.目标金额.sum()
    finally:
        con.close()
    prof = {c["display_name"]: c["profile"] for c in t1["columns"]}
    assert prof["基础信息_渠道"]["distinct"] == 8 and len(prof["基础信息_渠道"]["top"]) == 8
    assert prof["基础信息_日期"]["min"] == "2026-07-01"


def test_csv_gbk_end_to_end(env):
    up, truth = env
    plan, meta = _import(2, up / "marketing-sep-gbk.csv", "csv", "九月数据")
    b = plan["sheets"][0]["blocks"][0]
    assert b["name"] == "九月数据"
    cols = {c["name"]: c for c in b["columns"]}
    assert cols["日期"]["type"] == "DATE" and cols["消耗"]["type"] in ("DOUBLE", "BIGINT")
    assert cols["转化率"]["is_percent"]
    t = truth["csv"]
    assert meta["row_count"] == len(t)
    con = store.connect(2)
    try:
        got = con.execute("SELECT sum(c4), avg(c5) FROM t_1").fetchone()
        assert abs(got[0] - t.消耗.sum()) < 1 and abs(got[1] - t.转化率.mean()) < 1e-6
        assert con.execute("SELECT count(*) FROM t_1 WHERE c2='抖音直播' AND c1='2026-09-01'").fetchone()[0] == \
               len(t[(t.渠道 == "抖音直播") & (t.日期 == dt.date(2026, 9, 1))])
    finally:
        con.close()


def test_xls_merged_end_to_end(env):
    up, truth = env
    plan, meta = _import(3, up / "channel-legacy.xls", "xls")
    t = truth["xls"]
    assert meta["row_count"] == len(t)
    con = store.connect(3)
    try:
        # 纵向合并的渠道列已向下填充：每个渠道都能按地区统计
        got = dict(con.execute("SELECT c1, round(sum(c4),2) FROM t_1 GROUP BY 1").fetchall())
        exp = t.groupby("渠道").成交金额.sum().round(2)
        assert got.keys() == set(exp.index) and all(abs(got[k] - exp[k]) < 0.01 for k in exp.index)
        assert con.execute("SELECT count(*) FROM t_1 WHERE c1 IS NULL").fetchone()[0] == 0
    finally:
        con.close()


def test_refresh_block_manual_range_and_user_columns(env):
    up, _ = env
    plan = store.load_plan(1)
    b = plan["sheets"][0]["blocks"][0]
    b["columns"][1]["name"] = "渠道"
    b["columns"][3]["include"] = False
    nb = store.refresh_block(1, str(up / "marketing-2026Q3.xlsx"), "xlsx", "投放日报", "A4:I20", [4], b)
    assert nb["range"] == "A4:I20" and nb["header_rows"] == [4]
    assert nb["columns"][0]["name"] == "日期" and nb["columns"][1]["name"] == "渠道"
    assert nb["columns"][3]["include"] is False
    plan["sheets"][0]["blocks"] = [nb]
    plan["sheets"][1]["blocks"][0]["kind"] = "ignore"
    meta = store.ingest(1, str(up / "marketing-2026Q3.xlsx"), "xlsx", plan)
    assert meta["table_count"] == 1 and meta["row_count"] == 16
    assert len(meta["tables"][0]["columns"]) == 8
    p = store.preview(1, "t_1", 5)
    assert p["total"] == 16 and len(p["rows"]) == 5


def test_rejects_bad_files(env, tmp_path):
    up, _ = env
    fake = up / "fake.xlsx"
    fake.write_bytes(b"not a zip")
    with pytest.raises(DatasetError):
        store.analyze(9, str(fake), "xlsx", "")
    with pytest.raises(DatasetError):          # 上传目录之外的文件
        outside = tmp_path / "x.csv"
        outside.write_text("a,b\n1,2\n")
        store.analyze(9, str(outside), "csv", "")
    with pytest.raises(DatasetError):
        store.preview(1, "t_1; DROP TABLE t_1", 5)


def test_delete(env):
    store.delete(3)
    with pytest.raises(DatasetError):
        store.connect(3)


# ---------- M1+：sheet 内多块、备注、交叉表 ----------

from app.datasets import blocks as blockdet, pivot as pv


def test_xy_cut_and_header_split():
    g = [[None] * 8 for _ in range(12)]
    g[0][0] = "标题"
    for c, h in enumerate(["日期", "渠道", "金额"]):
        g[2][c] = h
    for r in range(3, 7):
        g[r][0], g[r][1], g[r][2] = f"2026-09-0{r}", "抖音", r * 10
    for c, h in enumerate(["地区", "目标", "实际"]):      # 紧接上表、无空行的新表头
        g[7][c] = h
    for r in range(8, 11):
        g[r][0], g[r][1], g[r][2] = "华东", 100, 90
    for c, h in enumerate(["渠道", "消耗"]):
        g[2][5 + c] = h
    for r in range(3, 6):
        g[r][5], g[r][6] = "抖音", 1.5
    found = blockdet.detect(g, 11, 7, truncated=False)
    got = [(f["kind"], f["range"]) for f in found]
    assert ("title", (0, 0, 0, 0)) in got
    assert ("data", (2, 0, 6, 2)) in got and ("data", (7, 0, 10, 2)) in got and ("data", (2, 5, 5, 6)) in got
    assert next(f for f in found if f["range"] == (2, 0, 6, 2))["title"] == "标题"


@pytest.mark.parametrize("name,exp", [
    ("1月", ("month", "2026-01-01")), ("2025年3月", ("month", "2025-03-01")), ("2026-12", ("month", "2026-12-01")),
    ("Q2", ("quarter", "2026Q2")), ("2025年一季度", ("quarter", "2025Q1")), ("2024年", ("year", 2024)),
    ("渠道", (None, None)), ("合计", (None, None)),
])
def test_parse_period(name, exp):
    assert pv.parse_period(name, 2026) == exp


def test_pivot_detect_rules():
    mk = lambda names, types: [{"col": i, "name": n, "type": t} for i, (n, t) in enumerate(zip(names, types))]
    p = pv.detect(mk(["地区", "1月", "2月", "3月", "合计"], ["VARCHAR"] + ["BIGINT"] * 4), 2026)
    assert p and p["drop_cols"] == [4] and len(p["items"]) == 3 and p["var_type"] == "DATE"
    assert pv.detect(mk(["日期", "渠道", "消耗", "订单"], ["DATE", "VARCHAR", "DOUBLE", "BIGINT"])) is None
    assert pv.detect(mk(["地区", "1月", "2月"], ["VARCHAR", "BIGINT", "BIGINT"])) is None       # 少于 3 列
    p2 = pv.detect(mk(["地区", "1月_消耗", "1月_成交", "2月_消耗", "2月_成交"], ["VARCHAR"] + ["DOUBLE"] * 4), 2026)
    assert p2 and p2["metrics"] == ["消耗", "成交"]


def test_complex_reports_end_to_end(env):
    up, truth = env
    cx = truth["complex"]
    path = up / "complex-reports.xlsx"
    plan = store.analyze(5, str(path), "xlsx", "")
    s1, s2 = plan["sheets"]
    data1 = {b["range"]: b for b in s1["blocks"] if b["kind"] == "data"}
    assert set(data1) == {"A3:H200", "A201:E209", "K3:N12"}
    assert [b["range"] for b in s1["blocks"] if b["kind"] == "note"] == ["A211:A213"]
    assert "不含税" in s1["notes"] and "支付时间" in s1["notes"]
    assert data1["A3:H200"]["name"] == "多块报表_九月投放明细与汇总"
    assert data1["K3:N12"]["totals_found"] == 1
    p1 = next(b for b in s2["blocks"] if b["kind"] == "data" and b["range"].startswith("A1:"))
    p2 = next(b for b in s2["blocks"] if b["kind"] == "data" and b["range"].startswith("A18:"))
    assert p1["pivot"]["enabled"] and p2["pivot"]["metrics"] == ["消耗", "成交"]
    assert p1["pivot"]["preview"][0][:2] == ["华东", "2026-01-01"]
    assert p1["pivot"]["value_name"] == "销售额"      # 从块标题推断
    meta = store.ingest(5, str(path), "xlsx", plan)
    t = {x["range_ref"]: x for x in meta["tables"]}
    assert t["A3:H200"]["row_count"] == len(cx["detail"]) and t["A201:E209"]["row_count"] == 8
    assert t["K3:N12"]["row_count"] == 8 and t["K3:N12"]["totals_dropped"] == 1
    assert "不含税" in t["A3:H200"]["notes"]
    tp1 = t[p1["range"]]
    assert [c["display_name"] for c in tp1["columns"]] == ["地区", "月份", "销售额"]
    assert tp1["row_count"] == len(cx["cross"]) and tp1["transform"]["type"] == "unpivot"
    tp2 = t[p2["range"]]
    assert [c["display_name"] for c in tp2["columns"]] == ["地区", "月份", "消耗", "成交"] and tp2["row_count"] == 24
    con = store.connect(5)
    try:
        ph = {k: v["physical_name"] for k, v in t.items()}
        d = cx["detail"]
        assert abs(con.execute(f"SELECT sum(c7) FROM {ph['A3:H200']}").fetchone()[0] - d.金额.sum()) < 0.01
        assert abs(con.execute(f"SELECT avg(c4) FROM {ph['A201:E209']}").fetchone()[0] - cx["target"].达成率.mean()) < 1e-3
        # 华东 3–5 月销售额合计 = 原表
        got = con.execute(f"SELECT sum(c3) FROM {ph[p1['range']]} WHERE c1='华东' AND c2 BETWEEN '2026-03-01' AND '2026-05-01'").fetchone()[0]
        c = cx["cross"]
        assert got == c[(c.地区 == "华东") & c.月.between(3, 5)].销售额.sum()
        assert con.execute(f"SELECT count(DISTINCT c2), min(c2) FROM {ph[p1['range']]}").fetchone() == (9, dt.date(2026, 1, 1))
        c2 = cx["cross2"]
        row = con.execute(f"SELECT sum(c3), sum(c4) FROM {ph[p2['range']]} WHERE c2='2026-02-01'").fetchone()
        assert abs(row[0] - c2[c2.月 == 2].消耗.sum()) < 0.01 and abs(row[1] - c2[c2.月 == 2].成交.sum()) < 0.01
    finally:
        con.close()


def test_pivot_disable_and_manual_block(env):
    up, _ = env
    path = up / "complex-reports.xlsx"
    plan = store.load_plan(5)
    s1, s2 = plan["sheets"]
    p1 = next(b for b in s2["blocks"] if b["kind"] == "data" and b["range"].startswith("A1:"))
    p1["pivot"]["enabled"] = False
    nb = store.refresh_block(5, str(path), "xlsx", s2["name"], p1["range"], None, p1)
    assert nb["pivot"]["enabled"] is False and nb["pivot"]["value_name"] == "销售额"   # 用户设置被保留
    # 手动框选：把渠道汇总只取 K3:M11（去掉金额列和合计行）
    k = next(b for b in s1["blocks"] if b["range"] == "K3:N12")
    nk = store.refresh_block(5, str(path), "xlsx", s1["name"], "K3:M11", None, k)
    assert [c["name"] for c in nk["columns"]] == ["渠道", "消耗", "订单"] and nk["manual"]
    s2["blocks"] = [nb if b is p1 else b for b in s2["blocks"]]
    s1["blocks"] = [nk if b is k else b for b in s1["blocks"]]
    for b in s1["blocks"]:
        if b["range"] == "A201:E209":
            b["kind"] = "ignore"
    meta = store.ingest(5, str(path), "xlsx", plan)
    t = {x["range_ref"]: x for x in meta["tables"]}
    assert "A201:E209" not in t and t["K3:M11"]["row_count"] == 8
    wide = t[nb["range"]]
    assert wide["transform"] is None and wide["row_count"] == 12 and len(wide["columns"]) == 11


def test_notes_only_go_to_plan_not_documents(env):
    """备注块不进文档问答：数据集模块不调用向量库"""
    import app.datasets.store as st
    assert "vectorstore" not in st.__dict__ and "chroma" not in open(st.__file__, encoding="utf8").read().lower()
