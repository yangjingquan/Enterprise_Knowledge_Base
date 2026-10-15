"""问数导入性能/内存：100 万行 csv（约 20MB），统计耗时和进程内存峰值。python -m tests.perf_datasets"""
import random, resource, sys, tempfile, time
from pathlib import Path
from app.datasets import store

tmp = Path(tempfile.mkdtemp()); up = tmp / "uploads"; up.mkdir()
store.UPLOAD_DIR, store.DATASET_DIR = up, tmp / "ds"
rnd = random.Random(1); ch = ["抖音", "小红书", "微信", "百度", "快手"]
p = up / "big.csv"
tot = 0.0
with open(p, "w", encoding="utf8") as f:
    f.write("日期,渠道,金额\n")
    for i in range(1_000_000):
        a = round(rnd.uniform(1, 999), 2); tot += a
        f.write(f"2026-{1 + i % 9:02d}-{1 + i % 28:02d},{ch[i % 5]},{a}\n")
print(f"文件 {p.stat().st_size / 1e6:.1f}MB")
base = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
t = time.time(); plan = store.analyze(1, str(p), "csv", "big"); ta = time.time() - t
t = time.time(); meta = store.ingest(1, str(p), "csv", plan); ti = time.time() - t
con = store.connect(1)
t = time.time(); r = con.execute("SELECT c2, sum(c3) FROM t_1 WHERE c1 BETWEEN '2026-03-01' AND '2026-05-31' GROUP BY 1").fetchall(); tq = time.time() - t
s = con.execute("SELECT sum(c3) FROM t_1").fetchone()[0]
peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
print(f"行数 {meta['row_count']}  分析 {ta:.1f}s  导入 {ti:.1f}s  聚合查询 {tq * 1000:.0f}ms  总和误差 {abs(s - tot):.4f}")
print(f"内存峰值 {peak:.0f}MB（导入前 {base:.0f}MB）  DuckDB 文件 {(tmp / 'ds' / '1.duckdb').stat().st_size / 1e6:.1f}MB")
sys.exit(0 if meta["row_count"] == 1_000_000 and abs(s - tot) < 0.01 else 1)
