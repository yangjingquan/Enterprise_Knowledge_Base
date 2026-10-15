"""
数据集导入：分析（生成导入方案）→ 用户确认 → 按方案写入 DuckDB。

文件布局（DATASET_DIR，生产环境在数据盘卷 ai-kb-datasets）：
  {id}.plan.json   导入方案（供前端预览/修改）
  {id}.duckdb      数据（每个数据块一张表 t_1、t_2…，列名 c1、c2…）
"""
import csv
import json
import os
import tempfile
import threading
from pathlib import Path

import duckdb

from . import blocks as blockdet
from . import infer, pivot as pv
from .reader import DatasetError, col_letter, format_range, open_workbook, parse_range

DATASET_DIR = Path(os.getenv("DATASET_DIR", "./datasets_data"))
UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", "../uploads"))
MAX_ROWS = int(os.getenv("DATASET_MAX_ROWS", "1000000"))
MAX_COLS = int(os.getenv("DATASET_MAX_COLS", "200"))
DUCKDB_MEMORY = os.getenv("DUCKDB_MEMORY_LIMIT", "256MB")
DUCKDB_THREADS = int(os.getenv("DUCKDB_THREADS", "2"))
SAMPLE_KEEP = infer.SAMPLE_ROWS + infer.HEADER_SCAN_ROWS
GRID_MAX_ROWS, GRID_MAX_COLS = 200, 50

# 导入与查询共用：同一时间只允许 1 个导入，避免内存叠加
INGEST_LOCK = threading.Semaphore(1)


def _paths(dataset_id: int):
    DATASET_DIR.mkdir(parents=True, exist_ok=True)
    return DATASET_DIR / f"{dataset_id}.plan.json", DATASET_DIR / f"{dataset_id}.duckdb"


def _check_path(file_path: str) -> Path:
    """只允许读取上传目录内的文件"""
    p = Path(file_path).resolve()
    root = UPLOAD_DIR.resolve()
    if root not in p.parents:
        raise DatasetError("文件路径不在上传目录内")
    if not p.is_file():
        raise DatasetError("文件不存在")
    return p


def connect(dataset_id: int, read_only=True):
    _, db = _paths(dataset_id)
    if read_only and not db.exists():
        raise DatasetError("数据集尚未导入")
    tmp = DATASET_DIR / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect(str(db), read_only=read_only, config={
        "memory_limit": DUCKDB_MEMORY, "threads": DUCKDB_THREADS, "temp_directory": str(tmp),
    })
    return con


# ---------- 合并单元格填充 ----------

class MergeFiller:
    """流式填充合并单元格：合并区域内所有单元格都取左上角的值（横向合并的分组表头、纵向合并的"渠道"列）"""

    def __init__(self, merges):
        self.merges = sorted(merges)
        self.vals = {}

    def fill(self, r: int, row: list) -> list:
        for m in self.merges:
            r1, c1, r2, c2 = m
            if r1 > r:
                break
            if r > r2:
                continue
            if r == r1:
                self.vals[m] = row[c1] if c1 < len(row) else None
            v = self.vals.get(m)
            if v is None:
                continue
            if len(row) <= c2:
                row.extend([None] * (c2 + 1 - len(row)))
            for c in range(c1, c2 + 1):
                if (r, c) != (r1, c1):
                    row[c] = v
        return row


def _cell_text(v) -> str:
    if infer.is_empty(v):
        return ""
    if hasattr(v, "hour") and not (v.hour or v.minute or v.second):
        return v.date().isoformat()
    s = infer.convert(v, "VARCHAR")
    return s[:50]


# ---------- 分析：生成导入方案 ----------

def _scan_sheet(sheet):
    """遍历一遍：取样本行（已填充合并）、统计行数和非空范围"""
    filler = MergeFiller(sheet.merges)
    sample, first, last, maxc, total = [], None, None, -1, 0
    totals_idx, raw = [], []
    for r, row in enumerate(sheet.rows()):
        if r < GRID_MAX_ROWS:
            raw.append(list(row))
        row = filler.fill(r, list(row))
        total = r + 1
        nz = [c for c, v in enumerate(row) if not infer.is_empty(v)]
        if nz:
            first = r if first is None else first
            last = r
            maxc = max(maxc, nz[-1])
            if r >= SAMPLE_KEEP and infer.is_total_row(row):
                totals_idx.append(r)
        if r < SAMPLE_KEEP:
            sample.append(row)
    minc = None
    for row in sample:
        for c, v in enumerate(row):
            if not infer.is_empty(v):
                minc = c if minc is None else min(minc, c)
                break
    return sample, first, last, (minc or 0), maxc, total, totals_idx, raw


def analyze_block(sample, rng, *, name, sheet_name, header_rows=None, year_hint=None, columns=None,
                  drop_totals=True, kind="data", block_id=None, total_rows=None, totals_idx=(),
                  pivot_user=None, note_text=None, manual=False):
    """
    对一个范围生成导入方案。header_rows：绝对行号（0 基）列表，None 表示自动识别。
    columns：用户修改过的列配置（按 col 绝对下标匹配），保留用户的 name/type/include。
    """
    r1, c1, r2, c2 = rng
    width = c2 - c1 + 1
    if width > MAX_COLS:
        raise DatasetError(f"列数 {width} 超过上限 {MAX_COLS}")
    rows = [(r, (sample[r] + [None] * (c2 + 1 - len(sample[r])))[c1:c2 + 1])
            for r in range(r1, min(r2, len(sample) - 1) + 1)]
    if header_rows is None:
        rel = infer.detect_header([x for _, x in rows], width)
        header_rows = [rows[i][0] for i in rel]
    header_vals = [x for r, x in rows if r in header_rows]
    data_start = (max(header_rows) + 1) if header_rows else r1
    names = infer.build_column_names(header_vals, width)
    data = [x for r, x in rows if r >= data_start and any(not infer.is_empty(v) for v in x)]
    totals = sum(1 for x in data if infer.is_total_row(x)) + sum(1 for r in totals_idx if data_start <= r <= r2)
    if drop_totals:
        data = [x for x in data if not infer.is_total_row(x)]
    data = data[:infer.SAMPLE_ROWS]
    user_cols = {c["col"]: c for c in (columns or [])}
    cols = []
    for i in range(width):
        vals = [x[i] for x in data]
        t = infer.infer_type(names[i], vals, year_hint)
        u = user_cols.get(c1 + i, {})
        col = {"col": c1 + i, "letter": col_letter(c1 + i),
               "name": u["name"] if u.get("name") and u.get("name") != u.get("orig_name") else names[i], "orig_name": names[i],
               "type": u.get("type") or t["type"], "auto_type": t["type"],
               "is_percent": t["is_percent"], "missing_year": t["missing_year"], "bad": t["bad"],
               "include": u.get("include", any(not infer.is_empty(v) for v in vals) or not names[i].startswith("列"))}
        cols.append(col)
    preview = [[infer.convert(x[i], cols[i]["type"], year_hint) for i in range(width)] for x in data[:20]]
    # 表头上方的单行文字作为表名候选（如"2026年各地区月度销售额"）
    title = None
    for r, x in rows:
        if header_rows and r < min(header_rows):
            vals = {str(v).strip() for v in x if not infer.is_empty(v)}
            if len(vals) == 1 and len(next(iter(vals))) <= 40:
                title = next(iter(vals))
                break
    # 交叉表识别：用户关闭过的保持关闭，改过的名称保留
    piv = pv.detect(cols, year_hint) if kind == "data" else None
    if piv:
        u = pivot_user or {}
        if not piv["metrics"]:
            piv["value_name"] = pv.guess_value_name(title, name) or piv["value_name"]
        piv["suggested"] = True
        piv["enabled"] = u.get("enabled", True)
        for k in ("var_name", "value_name", "metric_names"):
            if u.get(k):
                piv[k] = u[k]
        oc = pv.out_columns(piv, cols)
        piv["out_columns"] = [{"name": c["name"], "type": c["type"]} for c in oc]
        long_rows = []
        for x in data:
            long_rows.extend(pv.transform_row(piv, x, c1, year_hint))
            if len(long_rows) >= 20:
                break
        piv["preview"] = long_rows[:20]
    est_rows = None
    if total_rows is not None:
        est_rows = max(0, min(r2, total_rows - 1) - data_start + 1)
    return {"id": block_id, "sheet": sheet_name, "range": format_range(r1, c1, r2, c2), "kind": kind,
            "name": name, "header_rows": [r + 1 for r in header_rows],   # 对外用 Excel 行号（1 基）
            "drop_totals": drop_totals, "totals_found": totals, "est_rows": est_rows,
            "columns": cols, "preview": preview, "title": title, "pivot": piv,
            "note_text": note_text, "manual": manual}


def analyze(dataset_id: int, file_path: str, file_type: str, title: str) -> dict:
    """生成导入方案并保存。第一期：每个 sheet 的非空区域作为一个数据块"""
    p = _check_path(file_path)
    with INGEST_LOCK:
        sheets = open_workbook(str(p), file_type)
        plan = {"dataset_id": dataset_id, "file_type": file_type, "sheets": [], "warnings": []}
        for si, sh in enumerate(sheets):
            sample, first, last, minc, maxc, total, tidx, raw = _scan_sheet(sh)
            entry = {"name": sh.name, "hidden": sh.hidden, "rows": total, "cols": maxc + 1, "blocks": [],
                     "grid": [[_cell_text(v) for v in r[:GRID_MAX_COLS]] for r in raw],
                     "merges": [format_range(*m) for m in sh.merges if m[0] < GRID_MAX_ROWS][:500]}
            plan["sheets"].append(entry)
            if sh.hidden or first is None:
                entry["skipped"] = "隐藏的 sheet" if sh.hidden else "空 sheet"
                continue
            if total > MAX_ROWS + 50:
                raise DatasetError(f"sheet「{sh.name}」行数 {total} 超过上限 {MAX_ROWS}")
            year = infer.year_hint_from(sample)
            entry["year_hint"] = year
            found = blockdet.detect(sample, last, maxc, truncated=total > len(sample))
            n_data = sum(1 for f in found if f["kind"] == "data")
            notes = []
            for bi, f in enumerate(found):
                bid = f"s{si}b{bi}"
                if f["kind"] != "data":
                    r1, c1, r2, c2 = f["range"]
                    entry["blocks"].append({"id": bid, "sheet": sh.name, "range": format_range(r1, c1, r2, c2),
                                            "kind": "note" if f["kind"] == "note" else "ignore",
                                            "detected": f["kind"], "name": "", "note_text": f["note"]})
                    if f["kind"] == "note":
                        notes.append(f["note"])
                    continue
                blk = analyze_block(sample, f["range"], name="", sheet_name=sh.name, year_hint=year,
                                    block_id=bid, total_rows=total, totals_idx=tidx)
                if n_data == 1:
                    blk["name"] = (title or sh.name) if file_type == "csv" else sh.name
                else:
                    blk["name"] = f"{sh.name}_{f['title'] or blk['title'] or '块' + str(len([b for b in entry['blocks'] if b['kind'] == 'data']) + 1)}"
                blk["detected"] = "data"
                entry["blocks"].append(blk)
                if any(c["missing_year"] for c in blk["columns"]):
                    plan["warnings"].append(f"「{sh.name}」有日期缺少年份，已按 {year or '今年'} 年处理，请确认")
                if blk.get("pivot"):
                    plan["warnings"].append(f"「{blk['name']}」识别为交叉表，建议转换为长表后导入，请在预览中确认")
            entry["notes"] = "\n".join(notes)[:1000]
        if not any(b["kind"] == "data" for s in plan["sheets"] for b in s["blocks"]):
            raise DatasetError("文件中没有可导入的数据")
        plan_path, _ = _paths(dataset_id)
        plan_path.write_text(json.dumps(plan, ensure_ascii=False, default=str), encoding="utf8")
        return plan


def load_plan(dataset_id: int) -> dict:
    plan_path, _ = _paths(dataset_id)
    if not plan_path.exists():
        raise DatasetError("导入方案不存在，请重新上传")
    return json.loads(plan_path.read_text(encoding="utf8"))


def refresh_block(dataset_id: int, file_path: str, file_type: str, sheet: str, rng: str,
                  header_rows=None, block: dict | None = None) -> dict:
    """用户修改范围或表头行后重新识别该块（返回新方案块，不保存）"""
    p = _check_path(file_path)
    with INGEST_LOCK:
        sh = next((s for s in open_workbook(str(p), file_type) if s.name == sheet), None)
        if sh is None:
            raise DatasetError(f"找不到 sheet：{sheet}")
        sample, *_rest, total, tidx, _raw = _scan_sheet(sh)
        year = infer.year_hint_from(sample)
        hr = None if header_rows is None else [int(h) - 1 for h in header_rows]
        b = block or {}
        return analyze_block(sample, parse_range(rng), name=b.get("name") or sheet, sheet_name=sheet,
                             header_rows=hr, year_hint=year, columns=b.get("columns"),
                             drop_totals=b.get("drop_totals", True), kind=b.get("kind", "data"),
                             block_id=b.get("id"), total_rows=total, totals_idx=tidx,
                             pivot_user=b.get("pivot"), manual=True)


# ---------- 导入 ----------

DUCK_TYPES = {"DATE", "TIMESTAMP", "BIGINT", "DOUBLE", "VARCHAR"}


def _write_block_csv(sheet, block, year, out_path) -> dict:
    r1, c1, r2, c2 = parse_range(block["range"])
    header_rows = [h - 1 for h in block.get("header_rows") or []]
    data_start = (max(header_rows) + 1) if header_rows else r1
    cols = [c for c in block["columns"] if c.get("include", True)]
    piv = block.get("pivot") if (block.get("pivot") or {}).get("enabled") else None
    stats = {"rows": 0, "totals_dropped": 0, "bad": {c["col"]: 0 for c in cols}}
    filler = MergeFiller(sheet.merges)
    with open(out_path, "w", newline="", encoding="utf8") as f:
        w = csv.writer(f)
        for r, row in enumerate(sheet.rows()):
            if r > r2:
                break
            row = filler.fill(r, list(row))
            if r < data_start:
                continue
            seg = (row + [None] * (c2 + 1 - len(row)))[c1:c2 + 1]
            if all(infer.is_empty(v) for v in seg):
                continue
            if block.get("drop_totals", True) and infer.is_total_row(seg):
                stats["totals_dropped"] += 1
                continue
            if piv:
                for out in pv.transform_row(piv, seg, c1, year):
                    w.writerow(["" if x is None else x for x in out])
                    stats["rows"] += 1
                continue
            out = []
            for c in cols:
                v = seg[c["col"] - c1]
                x = infer.convert(v, c["type"], year)
                if x is None and not infer.is_empty(v):
                    stats["bad"][c["col"]] += 1
                out.append("" if x is None else x)
            w.writerow(out)
            stats["rows"] += 1
            if stats["rows"] > MAX_ROWS:
                raise DatasetError(f"「{block['name']}」超过 {MAX_ROWS} 行上限")
    return stats


def _profile(con, table, col, typ) -> dict:
    q = f'SELECT count("{col}"), count(DISTINCT "{col}"), min("{col}"), max("{col}") FROM {table}'
    nn, nd, mn, mx = con.execute(q).fetchone()
    prof = {"non_null": nn, "distinct": nd}
    if typ != "VARCHAR":
        prof.update({"min": None if mn is None else str(mn), "max": None if mx is None else str(mx)})
    if typ == "VARCHAR" and nd <= 50:
        top = con.execute(f'SELECT "{col}", count(*) n FROM {table} WHERE "{col}" IS NOT NULL '
                          f'GROUP BY 1 ORDER BY n DESC, 1 LIMIT 10').fetchall()
        prof["top"] = [[str(v)[:50], n] for v, n in top]
    return prof


def ingest(dataset_id: int, file_path: str, file_type: str, plan: dict) -> dict:
    """按确认后的方案写入 DuckDB（先写临时文件，成功后替换），返回表、列元数据"""
    p = _check_path(file_path)
    plan_path, db_path = _paths(dataset_id)
    with INGEST_LOCK:
        sheets = {s.name: s for s in open_workbook(str(p), file_type)}
        tmp_db = db_path.with_suffix(".duckdb.tmp")
        tmp_db.unlink(missing_ok=True)
        tmpdir = DATASET_DIR / "tmp"
        tmpdir.mkdir(parents=True, exist_ok=True)
        con = duckdb.connect(str(tmp_db), config={"memory_limit": DUCKDB_MEMORY, "threads": DUCKDB_THREADS,
                                                  "temp_directory": str(tmpdir)})
        tables, n = [], 0
        try:
            for s in plan["sheets"]:
                for b in s.get("blocks", []):
                    if b.get("kind", "data") != "data":
                        continue
                    sh = sheets.get(s["name"])
                    if sh is None:
                        raise DatasetError(f"找不到 sheet：{s['name']}")
                    piv = b.get("pivot") if (b.get("pivot") or {}).get("enabled") else None
                    cols = [c for c in b["columns"] if c.get("include", True)]
                    if piv:
                        cols_out = pv.out_columns(piv, b["columns"])
                    else:
                        cols_out = cols
                    if not cols:
                        continue
                    for c in cols_out:
                        if c["type"] not in DUCK_TYPES:
                            raise DatasetError(f"不支持的列类型：{c['type']}")
                    n += 1
                    table = f"t_{n}"
                    with tempfile.NamedTemporaryFile("w", suffix=".csv", dir=tmpdir, delete=False) as tf:
                        csv_path = tf.name
                    try:
                        st = _write_block_csv(sh, b, s.get("year_hint"), csv_path)
                        coldefs = ", ".join(f"'c{i + 1}': '{c['type']}'" for i, c in enumerate(cols_out))
                        con.execute(f"CREATE TABLE {table} AS SELECT * FROM read_csv(?, header=false, "
                                    f"columns={{{coldefs}}}, nullstr='', quote='\"', escape='\"', "
                                    f"dateformat='%Y-%m-%d', timestampformat='%Y-%m-%d %H:%M:%S')",
                                    [csv_path])
                    finally:
                        os.unlink(csv_path)
                    meta_cols = []
                    for i, c in enumerate(cols_out):
                        phys = f"c{i + 1}"
                        meta_cols.append({"ordinal": i + 1, "physical_name": phys, "display_name": c["name"],
                                          "data_type": c["type"], "is_percent": bool(c.get("is_percent")),
                                          "source_col": c.get("col"), "bad": st["bad"].get(c.get("col"), 0),
                                          "profile": _profile(con, table, phys, c["type"])})
                    tables.append({"block_id": b.get("id"), "sheet_name": s["name"], "physical_name": table,
                                   "display_name": b.get("name") or s["name"], "range_ref": b["range"],
                                   "header_row": (b.get("header_rows") or [None])[-1],
                                   "row_count": st["rows"], "totals_dropped": st["totals_dropped"],
                                   "transform": pv.transform_meta(piv, b["range"]) if piv else None,
                                   "notes": s.get("notes") or "",
                                   "columns": meta_cols})
            if not tables:
                raise DatasetError("没有选择任何要导入的数据块")
            con.execute("CHECKPOINT")
        finally:
            con.close()
        os.replace(tmp_db, db_path)
        Path(str(tmp_db) + ".wal").unlink(missing_ok=True)
        plan_path.write_text(json.dumps(plan, ensure_ascii=False, default=str), encoding="utf8")
        return {"tables": tables, "table_count": len(tables), "row_count": sum(t["row_count"] for t in tables)}


def preview(dataset_id: int, table: str, limit: int = 50) -> dict:
    if not (table.startswith("t_") and table[2:].isdigit()):
        raise DatasetError("表名不正确")
    limit = max(1, min(int(limit), 200))
    con = connect(dataset_id)
    try:
        cur = con.execute(f"SELECT * FROM {table} LIMIT {limit}")
        cols = [d[0] for d in cur.description]
        rows = [[None if v is None else (v if isinstance(v, (int, float)) else str(v)) for v in r]
                for r in cur.fetchall()]
        total = con.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
        return {"columns": cols, "rows": rows, "total": total}
    finally:
        con.close()


def delete(dataset_id: int):
    plan_path, db_path = _paths(dataset_id)
    for p in (plan_path, db_path, Path(str(db_path) + ".wal"), db_path.with_suffix(".duckdb.tmp")):
        p.unlink(missing_ok=True)
