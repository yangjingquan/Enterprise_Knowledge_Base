"""
向量化与存储。

- 向量模型：见 embeddings.py（默认阿里云百炼 text-embedding-v3，可切回本地 bge-m3）
- 向量库：Chroma，本地持久化到磁盘
- 所有文档放在同一个集合里（集合名随向量模型变化，见 embeddings.collection_name()），用元数据 document_id、kb_id 区分，
  按文档 / 按用户有权限的知识库检索时加过滤条件
- 每次写入、删除都同步更新 BM25 关键词索引（见 bm25.py），保证两路检索的文本一致
"""
import os
import threading
from functools import lru_cache
from langchain_chroma import Chroma

from . import bm25, embeddings

# 以下配置都可通过环境变量覆盖
CHROMA_DIR = os.getenv("CHROMA_DIR", "../chroma_db")
COLLECTION = embeddings.collection_name()
BATCH = 32   # 每批写入 32 块，避免一次性向量化过多导致内存暴涨
# 入库锁：多个文档同时上传时排队向量化。CPU 上并发跑多个 bge-m3 会互相抢线程，
# 实测 6 个文档并发比逐个处理慢十几倍，还会拖慢同时进行的问答检索
_INGEST_LOCK = threading.Lock()

@lru_cache
def get_store() -> Chroma:
    """
    获取向量库对象。@lru_cache 保证全局只创建一次（本地模型加载很慢，约 2GB；云端模式不加载模型）。
    使用余弦距离计算相似度。
    """
    emb = embeddings.get_embeddings()
    return Chroma(collection_name=COLLECTION, embedding_function=emb,
                  persist_directory=CHROMA_DIR, collection_metadata={"hnsw:space": "cosine"})

def add_chunks(document_id: int, chunks, kb_id: int | None = None):
    """把文本块向量化后写入 Chroma（并同步 BM25 索引），返回写入数量。"""
    store = get_store()
    # 每块都记录所属知识库，检索时按权限过滤
    for c in chunks:
        c.metadata["kb_id"] = kb_id if kb_id is not None else 0
    # 每块的 ID 形如 "文档ID_块序号"，保证唯一
    ids = [f"{document_id}_{c.metadata['chunk_index']}" for c in chunks]
    with _INGEST_LOCK:
        delete_document(document_id)  # 重复处理时先清掉旧向量
        for i in range(0, len(chunks), BATCH):
            store.add_documents(chunks[i:i+BATCH], ids=ids[i:i+BATCH])
        bm25.index.add(ids, [c.page_content for c in chunks], [c.metadata for c in chunks])
    return len(chunks)

def delete_document(document_id: int):
    """按 document_id 删除该文档的全部向量和 BM25 索引。"""
    get_store().delete(where={"document_id": document_id})
    bm25.index.delete_document(document_id)


def build_filter(document_id: int | None = None, kb_ids: list[int] | None = None):
    """
    组装 Chroma 的元数据过滤条件。
    kb_ids 为 None 表示不限制知识库（内部调试用）；Java 端总会传入用户有权限的知识库列表。
    """
    conds = []
    if document_id:
        conds.append({"document_id": document_id})
    if kb_ids is not None:
        conds.append({"kb_id": {"$in": list(kb_ids)}})
    if not conds:
        return None
    return conds[0] if len(conds) == 1 else {"$and": conds}


def set_document_kb(document_id: int, kb_id: int) -> int:
    """
    修改某文档全部文本块的 kb_id（文档移动到其他知识库、或老数据迁移到默认知识库时调用）。
    只改元数据，不重新向量化。返回修改的块数。
    """
    col = get_store()._collection
    res = col.get(where={"document_id": document_id}, include=["metadatas"])
    if not res["ids"]:
        return 0
    metas = [{**m, "kb_id": kb_id} for m in res["metadatas"]]
    col.update(ids=res["ids"], metadatas=metas)
    bm25.index.update_kb(document_id, kb_id)
    return len(res["ids"])


def sync_bm25():
    """启动时让 BM25 索引与 Chroma 对账。"""
    bm25.index.sync_from_chroma(get_store()._collection)

def search(query: str, k: int = 4, document_id: int | None = None, kb_ids: list[int] | None = None):
    """
    检索与问题最相似的 k 个文本块。
    返回 [(Document, 相似度)]，相似度范围约 0~1，越大越相关。
    指定 document_id 时只在该文档中检索；kb_ids 限定知识库范围（空列表表示无权限，返回空）。
    """
    if kb_ids is not None and len(kb_ids) == 0:
        return []
    flt = build_filter(document_id, kb_ids)
    # cosine 距离 -> 相似度 = 1 - 距离
    return [(d, 1 - dist) for d, dist in get_store().similarity_search_with_score(query, k=k, filter=flt)]


def get_context(document_id: int, chunk_index: int, window: int = 2):
    """
    取某个文本块及其前后各 window 块，按块序号排序返回，用于前端“查看原文上下文”。
    返回 [{"chunk_index", "page", "text", "sheet_name"}]。
    """
    lo = max(0, chunk_index - window)
    hi = chunk_index + window
    res = get_store().get(
        where={"$and": [
            {"document_id": document_id},
            {"chunk_index": {"$gte": lo}},
            {"chunk_index": {"$lte": hi}},
        ]},
        include=["documents", "metadatas"],
    )
    items = [
        {
            "chunk_index": m["chunk_index"],
            "page": m["page"],
            "sheet_name": m.get("sheet_name"),
            "slide_title": m.get("slide_title"),
            "text": t,
        }
        for t, m in zip(res["documents"], res["metadatas"])
    ]
    return sorted(items, key=lambda x: x["chunk_index"])
