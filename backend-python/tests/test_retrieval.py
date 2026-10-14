"""混合检索单元测试：BM25 分词与索引、知识库过滤、RRF 融合、重排阈值（向量检索和重排模型用假实现）。"""
from langchain_core.documents import Document

from app import bm25, retrieval
from app.bm25 import BM25Index, tokenize


def _meta(doc_id, idx, kb):
    return {"document_id": doc_id, "chunk_index": idx, "kb_id": kb, "file_name": f"d{doc_id}", "page": 1}


TEXTS = {
    "1_0": ("E-03 主轴过热报警：立即停止主轴，检查冷却机。", _meta(1, 0, 3)),
    "1_1": ("每日开机前检查导轨油位、气压和冷却液。", _meta(1, 1, 3)),
    "2_0": ("规格型号 LF-10 导轨润滑油滤芯，含税单价 85 元。", _meta(2, 0, 3)),
    "3_0": ("C类员工一线城市住宿每晚500元。", _meta(3, 0, 2)),
}


def _index():
    idx = BM25Index()
    idx.add(list(TEXTS), [t for t, _ in TEXTS.values()], [m for _, m in TEXTS.values()])
    return idx


def test_tokenize_codes_and_numbers():
    t = tokenize("SK-850 出现 E-03 报警，运行8200小时")
    for w in ("sk-850", "e-03", "e03", "8200", "报警"):
        assert w in t
    assert "的" not in tokenize("主轴的轴承")


def test_bm25_code_query_ranks_first():
    idx = _index()
    res = idx.search("E-03怎么处理", 5)
    assert res[0][0] == "1_0"
    assert idx.search("lf10 多少钱", 5)[0][0] == "2_0"     # 不带连字符也能命中


def test_bm25_kb_filter_and_delete():
    idx = _index()
    assert idx.search("住宿 500元", 5, kb_ids=[3]) == []    # 财务知识库无权限
    assert idx.search("住宿 500元", 5, kb_ids=[2])[0][0] == "3_0"
    assert idx.search("住宿", 5, kb_ids=[]) == []           # 无任何权限
    idx.update_kb(2, 9)
    assert idx.search("LF-10", 5, kb_ids=[9])[0][0] == "2_0"
    idx.delete_document(1)
    assert all(not r[0].startswith("1_") for r in idx.search("E-03 主轴", 5))


def _patch(monkeypatch, vec_ids, rerank_scores=None, enabled=True):
    idx = _index()
    monkeypatch.setattr(bm25, "index", idx)

    def fake_vec(q, k, document_id=None, kb_ids=None):
        out = []
        for cid, sim in vec_ids:
            text, meta = TEXTS[cid]
            if kb_ids is None or meta["kb_id"] in kb_ids:
                out.append((Document(page_content=text, metadata=meta), sim))
        return out
    monkeypatch.setattr(retrieval.vectorstore, "search", fake_vec)
    monkeypatch.setattr(retrieval.reranker, "enabled", lambda: enabled)
    monkeypatch.setattr(retrieval.reranker, "score",
                        lambda q, texts: [rerank_scores.get(t[:4], 0.0) for t in texts] if rerank_scores else [0.5] * len(texts))


def test_hybrid_keeps_bm25_hit_when_vector_weak(monkeypatch):
    # 向量检索把 E-03 排在后面且相似度低于阈值，BM25 把它救回来
    _patch(monkeypatch, [("1_1", 0.62), ("1_0", 0.41)])
    res = retrieval.retrieve("E-03怎么处理", 4, mode="hybrid")
    ids = [f"{d.metadata['document_id']}_{d.metadata['chunk_index']}" for d, _, _ in res]
    assert ids[0] == "1_0"
    vec_only = retrieval.retrieve("E-03怎么处理", 4, mode="vector")
    assert [d.metadata["chunk_index"] for d, _, _ in vec_only] == [1]   # 仅向量会漏掉


def test_rerank_threshold_and_order(monkeypatch):
    _patch(monkeypatch, [("1_1", 0.7), ("1_0", 0.6)], rerank_scores={"E-03": 0.95, "每日开机": 0.02})
    res = retrieval.retrieve("E-03怎么处理", 4, mode="rerank")
    assert len(res) == 1 and res[0][0].metadata["chunk_index"] == 0
    assert res[0][2]["rerank_score"] == 0.95


def test_rerank_disabled_falls_back(monkeypatch):
    _patch(monkeypatch, [("1_0", 0.7)], enabled=False)
    res = retrieval.retrieve("E-03", 4, mode="rerank")
    assert res and res[0][2]["rerank_score"] is None


def test_empty_permission_returns_nothing(monkeypatch):
    _patch(monkeypatch, [("1_0", 0.9)])
    assert retrieval.retrieve("E-03", 4, kb_ids=[]) == []
    # 只有人事知识库（2）权限时，看不到设备知识库（3）的内容
    res = retrieval.retrieve("E-03 主轴", 4, kb_ids=[2], mode="hybrid")
    assert all(d.metadata["kb_id"] == 2 for d, _, _ in res)
