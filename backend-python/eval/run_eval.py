"""
检索与问答效果评测。

用法（在 Python 服务容器内，文档已全部入库后）：
    python -m eval.run_eval                 # 三种检索模式对比 + 问答正确率（关键词匹配）
    python -m eval.run_eval --judge         # 额外用大模型判定答案是否正确（会多消耗 API 调用）
    python -m eval.run_eval --no-answer     # 只评测检索，不调用大模型生成答案（多轮改写仍会调用）
也可以由管理员在页面「效果评测」中点击运行（调用 /eval/run 接口）。

评测集 eval/dataset.json，每条包含：问题、对话历史（多轮）、期望出处（文件名，可选页码）、
答案必须包含的关键词（每组任意一个命中即可，所有组都要命中）、是否为拒答题。

指标：
  - Hit@k：前 k 个检索结果中是否有期望出处（文件名一致，指定了页码时页码也要一致）；
  - MRR：期望出处第一次出现的名次的倒数的平均值（越接近 1 越好）；
  - 拒答准确率：拒答题检索结果为空（被阈值过滤掉）的比例，及答案中包含“无法回答”的比例；
  - 答案正确率：答案包含全部关键词组；可选大模型裁判。
三种检索模式：vector（仅向量）、hybrid（向量 + BM25，RRF 融合）、rerank（混合 + 重排序）。
"""
import argparse
import json
import os
import time
import unicodedata
from datetime import datetime
from pathlib import Path

from app import rag, reranker, retrieval

DATASET = Path(__file__).with_name("dataset.json")
REPORT = Path(os.getenv("EVAL_REPORT", "/data/chroma_db/eval-report.md"))
MODES = ["vector", "hybrid", "rerank"]
K_LIST = [1, 3, 5]

# 给页面查询进度用的全局状态
STATUS = {"running": False, "progress": "", "finished_at": None, "error": None}


def _norm(s: str) -> str:
    return unicodedata.normalize("NFKC", s or "").replace(" ", "").lower()


def _hit_rank(results, src) -> int | None:
    """返回期望出处在结果中的名次（从 1 开始），没有命中返回 None。"""
    for i, (doc, _score, _detail) in enumerate(results, 1):
        m = doc.metadata
        if _norm(m["file_name"]) != _norm(src["file"]):
            continue
        if src.get("page") and int(m["page"]) != int(src["page"]):
            continue
        return i
    return None


def _keywords_ok(answer: str, groups) -> bool:
    a = _norm(answer)
    return all(any(_norm(w) in a for w in group) for group in groups)


def _judge(question, answer, groups) -> bool:
    """大模型裁判：给出标准要点，让模型判断答案是否正确。"""
    points = "；".join("/".join(g) for g in groups)
    resp = rag.client().chat.completions.create(
        model=rag.MODEL,
        temperature=0,
        max_tokens=5,
        messages=[{"role": "user", "content": (
            f"问题：{question}\n标准答案要点：{points}\n待评答案：{answer}\n"
            "待评答案是否正确包含了标准答案要点？只回答“是”或“否”。")}],
    )
    return "是" in (resp.choices[0].message.content or "")


def run(judge: bool = False, with_answer: bool = True, k: int = 5) -> str:
    items = json.loads(DATASET.read_text(encoding="utf-8"))
    modes = [m for m in MODES if m != "rerank" or reranker.enabled()]
    rows = []
    t0 = time.time()
    for n, it in enumerate(items, 1):
        STATUS["progress"] = f"{n}/{len(items)} {it['question']}"
        history = rag.normalize_history(it["history"])
        # 多轮题先改写（三种模式共用同一个改写结果，保证对比公平）
        q = rag.rewrite_question(it["question"], history)
        row = {"item": it, "rewritten": q if q != it["question"] else None, "modes": {}}
        for mode in modes:
            start = time.time()
            res = retrieval.retrieve(q, max(K_LIST), mode=mode)
            row["modes"][mode] = {
                "rank": _hit_rank(res, it["source"]) if it["source"] else None,
                "empty": len(res) == 0,
                "ms": (time.time() - start) * 1000,
                "top": [f"{d.metadata['file_name']}#p{d.metadata['page']}" for d, _, _ in res[:3]],
            }
        if with_answer:
            out = rag.answer(it["question"], history=it["history"], k=k)
            ans = out["answer"]
            row["answer"] = ans
            if it["negative"]:
                row["correct"] = "无法回答" in ans
            else:
                row["correct"] = _keywords_ok(ans, it["keywords"])
                if judge:
                    row["judge"] = _judge(it["question"], ans, it["keywords"])
        rows.append(row)
    report = _report(rows, modes, with_answer, judge, time.time() - t0)
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(report, encoding="utf-8")
    return report


def _pct(x, n):
    return f"{x / n * 100:.1f}%" if n else "-"


def _report(rows, modes, with_answer, judge, seconds) -> str:
    pos = [r for r in rows if not r["item"]["negative"]]
    neg = [r for r in rows if r["item"]["negative"]]
    L = [
        "# 检索与问答效果评测报告",
        "",
        f"- 评测时间：{datetime.now():%Y-%m-%d %H:%M}（UTC+8），耗时 {seconds:.0f} 秒",
        f"- 评测集：{len(rows)} 题（有答案 {len(pos)} 题，其中多轮 "
        f"{sum(1 for r in pos if r['item']['history'])} 题；拒答 {len(neg)} 题）",
        f"- 配置：候选数 {retrieval.CANDIDATES}，重排候选 {retrieval.RERANK_TOP_N}，"
        f"重排阈值 {retrieval.RERANK_THRESHOLD}，向量阈值 {retrieval.SCORE_THRESHOLD}，"
        f"重排模型 {reranker.RERANK_MODEL if reranker.enabled() else '未启用'}",
        "",
        "## 1. 检索指标对比",
        "",
        "| 模式 | " + " | ".join(f"Hit@{k}" for k in K_LIST) + " | MRR | 拒答（结果为空） | 平均耗时 |",
        "|---|" + "---|" * (len(K_LIST) + 3),
    ]
    for m in modes:
        ranks = [r["modes"][m]["rank"] for r in pos]
        hits = [sum(1 for x in ranks if x and x <= k) for k in K_LIST]
        mrr = sum(1 / x for x in ranks if x) / len(pos)
        empty = sum(1 for r in neg if r["modes"][m]["empty"])
        ms = sum(r["modes"][m]["ms"] for r in rows) / len(rows)
        L.append(f"| {m} | " + " | ".join(_pct(h, len(pos)) for h in hits)
                 + f" | {mrr:.3f} | {_pct(empty, len(neg))} | {ms:.0f} ms |")

    # 按题型
    cats = sorted({r["item"]["category"] for r in pos})
    L += ["", "## 2. 分题型 Hit@3", "", "| 题型 | 题数 | " + " | ".join(modes) + " |", "|---|---|" + "---|" * len(modes)]
    for c in cats:
        rs = [r for r in pos if r["item"]["category"] == c]
        L.append(f"| {c} | {len(rs)} | " + " | ".join(
            _pct(sum(1 for r in rs if r["modes"][m]["rank"] and r["modes"][m]["rank"] <= 3), len(rs)) for m in modes) + " |")

    if with_answer:
        ok = sum(1 for r in pos if r.get("correct"))
        neg_ok = sum(1 for r in neg if r.get("correct"))
        L += ["", "## 3. 问答正确率（默认检索模式：" + retrieval.DEFAULT_MODE + "）", "",
              f"- 关键词匹配正确率：{ok}/{len(pos)}（{_pct(ok, len(pos))}）",
              f"- 拒答正确率：{neg_ok}/{len(neg)}（{_pct(neg_ok, len(neg))}）"]
        if judge:
            j = sum(1 for r in pos if r.get("judge"))
            L.append(f"- 大模型裁判正确率：{j}/{len(pos)}（{_pct(j, len(pos))}）")

    L += ["", "## 4. 逐题明细", "", "| 编号 | 题型 | 问题 | 期望出处 | " + " | ".join(f"{m} 名次" for m in modes)
          + (" | 答案正确 |" if with_answer else " |"), "|---|---|---|---|" + "---|" * len(modes) + ("---|" if with_answer else "")]
    for r in rows:
        it = r["item"]
        src = f"{it['source']['file']}" + (f" 第{it['source']['page']}页" if it["source"].get("page") else "") if it["source"] else "（应拒答）"
        q = it["question"] + (f"（改写：{r['rewritten']}）" if r["rewritten"] else "")
        cells = []
        for m in modes:
            d = r["modes"][m]
            cells.append(("空 ✓" if d["empty"] else "未拒 ✗") if it["negative"] else (str(d["rank"]) if d["rank"] else "✗"))
        L.append(f"| {it['id']} | {it['category']} | {q} | {src} | " + " | ".join(cells)
                 + (f" | {'✓' if r.get('correct') else '✗'} |" if with_answer else " |"))

    wrong = [r for r in rows if with_answer and not r.get("correct")]
    if wrong:
        L += ["", "## 5. 答错的题目", ""]
        for r in wrong:
            L.append(f"- **{r['item']['id']} {r['item']['question']}**：{(r.get('answer') or '').strip()[:200]}")
    return "\n".join(L) + "\n"


def run_background(judge=False, with_answer=True):
    """供接口调用：在后台线程中运行，状态写入 STATUS。"""
    STATUS.update(running=True, error=None, progress="开始")
    try:
        run(judge=judge, with_answer=with_answer)
        STATUS["finished_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    except Exception as e:
        STATUS["error"] = str(e)
    finally:
        STATUS["running"] = False


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--no-answer", action="store_true")
    a = ap.parse_args()
    print(run(judge=a.judge, with_answer=not a.no_answer))
