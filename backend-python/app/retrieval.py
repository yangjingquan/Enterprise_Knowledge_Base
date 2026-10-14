"""
混合检索：向量检索 + BM25 关键词检索 → RRF 融合 → 交叉编码器重排序 → 阈值过滤。

  1. 向量检索取前 CANDIDATES 个（默认 20），擅长语义相近的表述；
  2. BM25 取前 CANDIDATES 个，擅长编号、型号、数字等精确匹配；
  3. RRF（倒数排名融合）：每个块的融合分 = Σ 1 / (60 + 它在各路结果中的名次)。
     只看名次不看原始分数，两路分数尺度不同也能公平合并；
  4. 取融合后前 RERANK_TOP_N 个（默认 15）交给 bge-reranker-v2-m3 重新打分，
     低于 RERANK_THRESHOLD 的丢弃（用来拒答无关问题），按重排分取前 k 个。
  未启用重排时：保留“向量相似度达到阈值”或“BM25 排名前 3”的块，按融合分排序。

三种模式（便于评测对比）：vector（仅向量，原有逻辑）、hybrid（混合不重排）、rerank（混合 + 重排）。
默认模式由 RETRIEVAL_MODE 决定；启用重排时为 rerank，否则为 hybrid。
"""
import os

from langchain_core.documents import Document

from . import bm25, reranker, vectorstore

# 向量检索相似度阈值（vector / hybrid 模式使用）
SCORE_THRESHOLD = float(os.getenv("SCORE_THRESHOLD", "0.5"))
# 重排序分数阈值（0~1）：低于此值视为不相关。该值根据 docs/eval-report.md 的评测结果确定
RERANK_THRESHOLD = float(os.getenv("RERANK_THRESHOLD", "0.15"))
# 每路检索的候选数
CANDIDATES = int(os.getenv("RETRIEVAL_CANDIDATES", "20"))
# 交给重排序模型的候选数：越多越准，但 CPU 上越慢（每个候选约 0.1~0.3 秒）
RERANK_TOP_N = int(os.getenv("RERANK_TOP_N", "15"))
# RRF 常数，业界通用取 60
RRF_K = 60
# hybrid 模式下，BM25 排名前几的块即使向量分不够也保留（编号类问题常见）
BM25_KEEP_RANK = 3

DEFAULT_MODE = os.getenv("RETRIEVAL_MODE", "rerank" if reranker.enabled() else "hybrid")


def retrieve(question: str, k: int, document_id=None, kb_ids=None, mode: str | None = None):
    """
    检索入口。返回 [(Document, 展示分数, 详情)]，按相关度从高到低。
    详情包含 vector_score、bm25_rank、rerank_score，便于调试和评测。
    """
    mode = mode or DEFAULT_MODE
    if mode == "rerank" and not reranker.enabled():
        mode = "hybrid"
    if kb_ids is not None and len(kb_ids) == 0:
        return []   # 用户没有任何知识库权限

    # ---------- 1. 向量检索 ----------
    vec = vectorstore.search(question, CANDIDATES if mode != "vector" else k, document_id, kb_ids)
    if mode == "vector":
        return [(d, s, {"vector_score": round(s, 4)}) for d, s in vec if s >= SCORE_THRESHOLD][:k]

    # ---------- 2. BM25 检索 ----------
    kw = bm25.index.search(question, CANDIDATES, document_id, kb_ids)

    # ---------- 3. RRF 融合 ----------
    pool = {}   # id -> {"doc", "rrf", "vector_score", "bm25_rank"}
    for rank, (doc, sim) in enumerate(vec, 1):
        cid = _chunk_id(doc.metadata)
        item = pool.setdefault(cid, {"doc": doc, "rrf": 0.0, "vector_score": None, "bm25_rank": None})
        item["rrf"] += 1 / (RRF_K + rank)
        item["vector_score"] = sim
    for rank, (cid, text, meta, _score) in enumerate(kw, 1):
        item = pool.setdefault(cid, {
            "doc": Document(page_content=text, metadata=meta),
            "rrf": 0.0, "vector_score": None, "bm25_rank": None,
        })
        item["rrf"] += 1 / (RRF_K + rank)
        item["bm25_rank"] = rank
    fused = sorted(pool.values(), key=lambda x: -x["rrf"])

    # ---------- 4a. 不重排：按阈值保留 ----------
    if mode == "hybrid":
        kept = [
            x for x in fused
            if (x["vector_score"] or 0) >= SCORE_THRESHOLD
            or (x["bm25_rank"] is not None and x["bm25_rank"] <= BM25_KEEP_RANK)
        ]
        return [(x["doc"], round(x["rrf"] * 30, 4), _detail(x)) for x in kept[:k]]

    # ---------- 4b. 重排序 ----------
    cands = fused[:RERANK_TOP_N]
    scores = reranker.score(question, [x["doc"].page_content for x in cands])
    for x, s in zip(cands, scores):
        x["rerank_score"] = s
    cands = [x for x in cands if x["rerank_score"] >= RERANK_THRESHOLD]
    cands.sort(key=lambda x: -x["rerank_score"])
    return [(x["doc"], round(x["rerank_score"], 4), _detail(x)) for x in cands[:k]]


def _chunk_id(meta: dict) -> str:
    """与 vectorstore 中的 ID 规则一致：文档ID_块序号"""
    return f"{meta['document_id']}_{meta['chunk_index']}"


def _detail(x: dict) -> dict:
    return {
        "vector_score": round(x["vector_score"], 4) if x["vector_score"] is not None else None,
        "bm25_rank": x["bm25_rank"],
        "rerank_score": round(x["rerank_score"], 4) if x.get("rerank_score") is not None else None,
    }
