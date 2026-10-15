"""
问数第一期 API 端到端测试（需先启动 Python 8000 与 Java 8080）。
python -m tests.e2e_datasets_api [http://localhost:8080]
"""
import json
import sys
import tempfile
import time
from pathlib import Path

import requests

from tools.gen_sample_datasets import generate

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
DEMO = Path(__file__).resolve().parents[2] / "demo-data"
results = []


def check(name, cond, info=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (f"  [{info}]" if info and not cond else ""))


def login(u, p):
    r = requests.post(f"{BASE}/auth/login", json={"username": u, "password": p})
    r.raise_for_status()
    return {"Authorization": "Bearer " + r.json()["token"]}


def wait(h, did, states, timeout=300):
    t0 = time.time()
    while time.time() - t0 < timeout:
        d = requests.get(f"{BASE}/datasets/{did}", headers=h).json()["dataset"]
        if d["status"] in states:
            return d, time.time() - t0
        time.sleep(0.5)
    return d, time.time() - t0


def upload(h, path, kb):
    with open(path, "rb") as f:
        return requests.post(f"{BASE}/datasets/upload", headers=h, files={"file": (Path(path).name, f)}, data={"kbId": kb})


def full_import(h, path, kb):
    r = upload(h, path, kb)
    did = r.json()["id"]
    d, t1 = wait(h, did, {"awaiting_confirm", "failed"})
    plan = requests.get(f"{BASE}/datasets/{did}/plan", headers=h).json()
    r = requests.put(f"{BASE}/datasets/{did}/plan", headers=h, json=plan)
    d, t2 = wait(h, did, {"ready", "failed"})
    return did, d, plan, t1, t2


def main():
    admin, fin, hr, eng = (login("admin", "admin123"), login("fin01", "Fin@2026"),
                           login("hr01", "Hr@2026"), login("eng01", "Eng@2026"))
    tmp = Path(tempfile.mkdtemp())
    truth = generate(tmp)
    df = truth["daily"]

    # 1. fin01 向「财务制度」(kb 3, 私有) 上传 xlsx
    did, d, plan, t1, t2 = full_import(fin, tmp / "marketing-2026Q3.xlsx", 3)
    check("xlsx 导入成功", d["status"] == "ready", d.get("errorMsg"))
    print(f"   分析 {t1:.1f}s，导入 {t2:.1f}s")
    check("xlsx 2 张表，行数 = 标准答案", d["tableCount"] == 2 and d["rowCount"] == len(df) + len(truth["targets"]),
          f'{d["tableCount"]} {d["rowCount"]}')
    det = requests.get(f"{BASE}/datasets/{did}", headers=fin).json()
    t = det["tables"][0]
    cols = {c["displayName"]: c for c in t["columns"]}
    prof = json.loads(cols["基础信息_日期"]["profile"])
    check("日期列画像 2026-07-01~2026-09-30", prof["min"] == "2026-07-01" and prof["max"] == "2026-09-30", prof)
    check("渠道 Top 取值 8 个", len(json.loads(cols["基础信息_渠道"]["profile"])["top"]) == 8)
    check("隐藏 sheet 被跳过", not any(x["table"]["sheetName"] == "隐藏备份" for x in det["tables"]))
    p = requests.get(f"{BASE}/datasets/{did}/preview", headers=fin, params={"table": "t_1", "limit": 5}).json()
    check("预览 5 行", len(p["rows"]) == 5 and p["total"] == len(df))
    # 权限
    check("hr01 看不到财务库数据集（404）", requests.get(f"{BASE}/datasets/{did}", headers=hr).status_code == 404)
    check("hr01 列表不含该数据集", did not in [x["id"] for x in requests.get(f"{BASE}/datasets", headers=hr).json()])
    with open(tmp / "channel-legacy.xls", "rb") as f:
        r = requests.post(f"{BASE}/datasets/upload", headers=hr, files={"file": ("a.xls", f)}, data={"kbId": 3})
    check("hr01 不能向财务库上传（403）", r.status_code == 403)
    check("eng01 不能删除（404）", requests.delete(f"{BASE}/datasets/{did}", headers=eng).status_code == 404)
    check("hr01 预览被拒（404）", requests.get(f"{BASE}/datasets/{did}/preview", headers=hr,
                                            params={"table": "t_1"}).status_code == 404)
    # 修改列描述
    c = cols["投放数据_消耗(元)"]
    r = requests.put(f"{BASE}/datasets/{did}/columns", headers=fin, json={"description": "Q3 投放", "tables": [
        {"id": t["table"]["id"], "displayName": "投放日报", "columns": [{"id": c["id"], "description": "含税", "synonyms": "花费,成本"}]}]})
    det2 = r.json()
    c2 = next(x for x in det2["tables"][0]["columns"] if x["id"] == c["id"])
    check("列描述/同义词保存", c2["synonyms"] == "花费,成本" and det2["tables"][0]["table"]["displayName"] == "投放日报")
    # 手动范围 + 重新导入（保留描述）
    blk = plan["sheets"][0]["blocks"][0]
    nb = requests.post(f"{BASE}/datasets/{did}/plan/block", headers=fin, json={
        "sheet": "投放日报", "range": "A3:I104", "header_rows": [3, 4], "block": blk}).json()
    check("手动范围 A3:I104 重新识别", nb.get("range") == "A3:I104", nb)
    plan["sheets"][0]["blocks"] = [nb]
    requests.put(f"{BASE}/datasets/{did}/plan", headers=fin, json=plan)
    d, _ = wait(fin, did, {"ready", "failed"})
    det3 = requests.get(f"{BASE}/datasets/{did}", headers=fin).json()
    c3 = next(x for x in det3["tables"][0]["columns"] if x["displayName"] == "投放数据_消耗(元)")
    check("重新导入 100 行且保留列描述", det3["tables"][0]["table"]["rowCount"] == 100 and c3["synonyms"] == "花费,成本",
          det3["tables"][0]["table"]["rowCount"])

    # 2. admin 向公共库上传 csv / xls，hr01 可读
    did2, d2, *_ = full_import(admin, tmp / "marketing-sep-gbk.csv", 1)
    check("GBK csv 导入，行数一致", d2["status"] == "ready" and d2["rowCount"] == len(truth["csv"]), d2)
    did3, d3, *_ = full_import(admin, tmp / "channel-legacy.xls", 1)
    check("xls 导入，行数一致", d3["status"] == "ready" and d3["rowCount"] == len(truth["xls"]), d3)
    p = requests.get(f"{BASE}/datasets/{did3}/preview", headers=hr, params={"table": "t_1", "limit": 200}).json()
    check("hr01 可预览公共库；xls 合并单元格已填充", p["total"] == 64 and all(r[0] for r in p["rows"]))

    # 2b. M1+ 复杂报表：多块 + 备注 + 交叉表
    did5, d5, plan5, *_ = full_import(admin, tmp / "complex-reports.xlsx", 1)
    check("复杂报表导入成功：5 张表", d5["status"] == "ready" and d5["tableCount"] == 5, d5)
    det5 = requests.get(f"{BASE}/datasets/{did5}", headers=hr).json()
    notes = det5.get("notes") or []
    check("备注块保存为口径说明", len(notes) == 1 and "不含税" in notes[0]["noteText"], notes)
    tf = [x for x in det5["tables"] if x["table"]["transform"]]
    check("2 张表由交叉表转换", len(tf) == 2, [x["table"]["displayName"] for x in tf])
    cross = next(x for x in tf if len(x["columns"]) == 3)
    check("交叉表转长表 108 行，列 地区/月份/销售额（列名由标题推断）", cross["table"]["rowCount"] == 108 and
          [c["displayName"] for c in cross["columns"]] == ["地区", "月份", "销售额"], cross["table"])
    docs_titles = [x["fileName"] for x in requests.get(f"{BASE}/documents", headers=admin).json()]
    check("数据集/备注没有进入文档列表", "complex-reports.xlsx" not in docs_titles)
    # 关闭逆透视 + 手动块，重新导入
    s2 = plan5["sheets"][1]
    pb = next(b for b in s2["blocks"] if b.get("pivot"))
    pb["pivot"]["enabled"] = False
    requests.put(f"{BASE}/datasets/{did5}/plan", headers=admin, json=plan5)
    d5b, _ = wait(admin, did5, {"ready", "failed"})
    det5b = requests.get(f"{BASE}/datasets/{did5}", headers=admin).json()
    check("关闭逆透视后只剩 1 张转换表", d5b["status"] == "ready" and
          sum(1 for x in det5b["tables"] if x["table"]["transform"]) == 1, d5b)

    # 3. 异常文件
    bad = tmp / "bad.xlsx"
    bad.write_bytes(b"hello world")
    check("伪造 xlsx 被拒（400）", upload(admin, bad, 1).status_code == 400)
    pdf = DEMO / "员工手册.pdf"
    check("pdf 不能作为数据集（400）", upload(admin, pdf, 1).status_code == 400)
    empty = tmp / "empty.csv"
    empty.write_text("\n\n")
    r = upload(admin, empty, 1)
    d, _ = wait(admin, r.json()["id"], {"awaiting_confirm", "failed"})
    check("空 csv 显示失败原因", d["status"] == "failed" and "没有可导入" in (d["errorMsg"] or ""), d)
    requests.delete(f"{BASE}/datasets/{d['id']}", headers=admin)

    # 4. 知识库还有数据集时不能删除
    r = requests.post(f"{BASE}/admin/kbs", headers=admin, json={"name": "临时库e2e", "visibility": "private"})
    kb_tmp = r.json()["id"]
    did4, *_ = full_import(admin, tmp / "channel-legacy.xls", kb_tmp)
    check("有数据集的知识库不能删除", requests.delete(f"{BASE}/admin/kbs/{kb_tmp}", headers=admin).status_code == 400)
    check("删除数据集", requests.delete(f"{BASE}/datasets/{did4}", headers=admin).status_code == 200)
    check("删除后 404", requests.get(f"{BASE}/datasets/{did4}", headers=admin).status_code == 404)
    check("再删知识库成功", requests.delete(f"{BASE}/admin/kbs/{kb_tmp}", headers=admin).status_code == 200)

    # 5. 回归：文档问答
    with open(DEMO / "员工手册.pdf", "rb") as f:
        r = requests.post(f"{BASE}/documents/upload", headers=admin, files={"file": ("员工手册.pdf", f)}, data={"kbId": 1})
    doc_id = r.json()["id"]
    for _ in range(240):
        docs = {x["id"]: x for x in requests.get(f"{BASE}/documents", headers=admin).json()}
        if docs[doc_id]["status"] in ("ready", "failed"):
            break
        time.sleep(1)
    check("回归：文档入库", docs[doc_id]["status"] == "ready", docs[doc_id]["status"])
    r = requests.post(f"{BASE}/qa/ask", headers=hr, json={"question": "工作满10年年假有几天？"}, timeout=180).json()
    check("回归：文档问答有答案和引用", r.get("answer") and r.get("sources"), str(r)[:200])
    print("   答案：", (r.get("answer") or "")[:80].replace("\n", " "))
    with requests.post(f"{BASE}/qa/ask/stream", headers=hr, json={"question": "迟到怎么处理？"}, stream=True, timeout=180) as s:
        types = [json.loads(l[6:])["type"] for l in (x.decode("utf8") for x in s.iter_lines()) if l.startswith("data: ")]
    check("回归：流式问答事件完整", "delta" in types and "saved" in types and types[-1] == "done", types[-5:])
    # 清理本次上传的测试文档，避免测试环境里重复的"员工手册.pdf"越积越多
    check("清理：删除测试上传的文档", requests.delete(f"{BASE}/documents/{doc_id}", headers=admin).status_code == 200)

    n = sum(ok for _, ok in results)
    print(f"\n{n}/{len(results)} 通过")
    sys.exit(0 if n == len(results) else 1)


if __name__ == "__main__":
    main()
