"""百炼云端向量 / 重排 / 重建索引测试：使用本地模拟接口（tools/mock_dashscope.py），不需要真实 key。"""
import importlib
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import mock_dashscope  # noqa: E402


@pytest.fixture(scope="module")
def mock_url():
    srv = mock_dashscope.serve(0)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def test_embeddings_batching_and_dim(mock_url):
    from app.embeddings import DashScopeEmbeddings
    before = mock_dashscope.CALLS["embeddings"]
    emb = DashScopeEmbeddings(api_key="test", base_url=mock_url + "/compatible-mode/v1", batch=10)
    vecs = emb.embed_documents([f"第{i}条 制度条款" for i in range(23)])
    assert len(vecs) == 23 and len(vecs[0]) == 1024
    assert mock_dashscope.CALLS["embeddings"] - before == 3          # 10 + 10 + 3
    assert emb.embed_query("第1条 制度条款") == vecs[1]               # 确定性


def test_embeddings_retry_then_fail(monkeypatch):
    from app import embeddings
    monkeypatch.setattr(embeddings.time, "sleep", lambda s: None)
    emb = embeddings.DashScopeEmbeddings(api_key="test", base_url="http://127.0.0.1:1/v1", retries=2, timeout=1)
    with pytest.raises(RuntimeError, match="重试"):
        emb.embed_query("x")


def test_embeddings_requires_key():
    from app.embeddings import DashScopeEmbeddings
    with pytest.raises(RuntimeError, match="DASHSCOPE_API_KEY"):
        DashScopeEmbeddings(api_key="").embed_query("x")


def test_collection_names(monkeypatch):
    from app import embeddings
    monkeypatch.delenv("CHROMA_COLLECTION", raising=False)
    monkeypatch.setattr(embeddings, "EMBEDDING_PROVIDER", "dashscope")
    assert embeddings.collection_name() == "kb_docs_dashscope_v3_1024"
    monkeypatch.setattr(embeddings, "EMBEDDING_PROVIDER", "local")
    assert embeddings.collection_name() == "kb_docs"


def test_rerank_provider_resolution(monkeypatch):
    from app import reranker
    for env, want in [({"RERANK_PROVIDER": "none"}, "none"),
                      ({"RERANK_ENABLED": "false"}, "none"),
                      ({"EMBEDDING_PROVIDER": "local"}, "local"),
                      ({}, "dashscope")]:
        for k in ("RERANK_PROVIDER", "RERANK_ENABLED", "EMBEDDING_PROVIDER"):
            monkeypatch.delenv(k, raising=False)
        for k, v in env.items():
            monkeypatch.setenv(k, v)
        assert reranker._provider() == want


def test_dashscope_rerank(mock_url, monkeypatch):
    from app import reranker
    monkeypatch.setenv("DASHSCOPE_API_KEY", "test")
    monkeypatch.setattr(reranker, "RERANK_PROVIDER", "dashscope")
    monkeypatch.setattr(reranker, "DASHSCOPE_RERANK_URL",
                        mock_url + "/api/v1/services/rerank/text-rerank/text-rerank")
    s = reranker.score("主轴过热报警", ["今天天气不错", "主轴过热报警怎么处理"])
    assert len(s) == 2 and s[1] > s[0]          # 结果按原顺序返回


def test_reindex_copies_old_collection(mock_url, tmp_path, monkeypatch):
    """模拟升级：老集合 kb_docs（bge-m3 向量）→ 新集合 kb_docs_dashscope_v3_1024。"""
    import chromadb
    monkeypatch.setenv("CHROMA_DIR", str(tmp_path))
    monkeypatch.setenv("EMBEDDING_PROVIDER", "dashscope")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "test")
    monkeypatch.setenv("DASHSCOPE_BASE_URL", mock_url + "/compatible-mode/v1")
    monkeypatch.setenv("BM25_CACHE", str(tmp_path / "bm25.json"))
    old = chromadb.PersistentClient(path=str(tmp_path)).get_or_create_collection(
        "kb_docs", metadata={"hnsw:space": "cosine"})
    texts = ["E-03 主轴过热报警：立即停止主轴。", "C类员工一线城市住宿每晚500元。", "导轨润滑油滤芯 LF-10 单价 85 元。"] * 5
    ids = [f"{i % 3 + 1}_{i}" for i in range(len(texts))]
    metas = [{"document_id": i % 3 + 1, "chunk_index": i, "kb_id": 1, "file_name": "a.pdf", "page": 1}
             for i in range(len(texts))]
    old.add(ids=ids, documents=texts, metadatas=metas, embeddings=[[0.1] * 8] * len(texts))  # 维度不同于新模型

    from app import bm25, embeddings, vectorstore, reindex
    for m in (embeddings, bm25, vectorstore, reindex):
        importlib.reload(m)
    assert vectorstore.COLLECTION == "kb_docs_dashscope_v3_1024"
    r = reindex.run()
    assert r["source"] == "kb_docs" and r["embedded"] == 15
    assert reindex.run()["skipped"] == 15                     # 断点续跑：已有的跳过
    hits = vectorstore.search("主轴过热怎么办", k=1, kb_ids=[1])
    assert "主轴过热" in hits[0][0].page_content
    assert hits[0][0].metadata["document_id"] == 1
