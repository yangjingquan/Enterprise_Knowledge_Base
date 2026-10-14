"""
重建向量索引：把已入库的文本块用“当前向量模型”重新向量化，写入当前集合。

切换 EMBEDDING_PROVIDER / 模型后运行一次即可，无需重新上传文件：
  docker compose exec backend-python python -m app.reindex            # 从旧集合复制文本重算向量
  docker compose exec backend-python python -m app.reindex --source kb_docs --force
也可调用接口 POST /admin/reindex（后台执行），GET /admin/reindex 查看进度。

文本来源：同一 Chroma 目录下的其他集合（默认自动选块数最多的那个，通常是老的 kb_docs）。
块 ID、文本、元数据（文档ID、页码、知识库等）原样保留，只替换向量，所以 MySQL 中的记录和引用不受影响。
已在目标集合中的块默认跳过（可断点续跑），--force 则全部重算。
"""
import argparse
import logging
import threading

import chromadb

from . import vectorstore

log = logging.getLogger(__name__)
STATUS = {"running": False, "done": 0, "total": 0, "source": None, "target": vectorstore.COLLECTION, "error": None}
_LOCK = threading.Lock()
PAGE = 200   # 每次从源集合读取的块数


def _client():
    return chromadb.PersistentClient(path=vectorstore.CHROMA_DIR)


def pick_source(client, target):
    """选择源集合：除目标集合外块数最多的那个。"""
    best = None
    for c in client.list_collections():
        name = c if isinstance(c, str) else c.name
        if name == target:
            continue
        n = client.get_collection(name).count()
        if n and (best is None or n > best[1]):
            best = (name, n)
    return best[0] if best else None


def run(source: str | None = None, force: bool = False):
    """执行重建，返回 {"source","target","total","embedded","skipped"}。"""
    if not _LOCK.acquire(blocking=False):
        raise RuntimeError("重建索引正在进行中")
    try:
        STATUS.update(running=True, done=0, total=0, error=None)
        client = _client()
        target = vectorstore.COLLECTION
        source = source or pick_source(client, target)
        STATUS["source"] = source
        if not source:
            log.info("没有找到可复制的旧集合，无需重建")
            return {"source": None, "target": target, "total": 0, "embedded": 0, "skipped": 0}
        src = client.get_collection(source)
        store = vectorstore.get_store()
        col = store._collection
        total = src.count()
        STATUS["total"] = total
        embedded = skipped = 0
        for off in range(0, total, PAGE):
            page = src.get(include=["documents", "metadatas"], limit=PAGE, offset=off)
            ids, docs, metas = page["ids"], page["documents"], page["metadatas"]
            if not force and ids:
                have = set(col.get(ids=ids, include=[])["ids"])
                keep = [i for i, x in enumerate(ids) if x not in have]
                skipped += len(ids) - len(keep)
                ids, docs, metas = [ids[i] for i in keep], [docs[i] for i in keep], [metas[i] for i in keep]
            if ids:
                vecs = store.embeddings.embed_documents(docs)   # 内部按 10 条一批调用
                col.upsert(ids=ids, documents=docs, metadatas=metas, embeddings=vecs)
                embedded += len(ids)
            STATUS["done"] = min(off + PAGE, total)
            log.info("重建索引 %s → %s：%d/%d", source, target, STATUS["done"], total)
        vectorstore.sync_bm25()
        return {"source": source, "target": target, "total": total, "embedded": embedded, "skipped": skipped}
    except Exception as e:
        STATUS["error"] = str(e)
        raise
    finally:
        STATUS["running"] = False
        _LOCK.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    ap = argparse.ArgumentParser(description="用当前向量模型重建 Chroma 索引")
    ap.add_argument("--source", help="源集合名（默认自动选择）")
    ap.add_argument("--force", action="store_true", help="目标集合已有的块也重新计算")
    a = ap.parse_args()
    print(run(a.source, a.force))
    print("完成。若 RAG 服务正在运行，请重启它以加载新的 BM25 索引：docker compose restart backend-python")
