"""
# 部署流水线：代码变化时只重建本镜像的源码层，依赖层走缓存
FastAPI 入口：Python RAG 服务。

Java 后端负责用户、文档记录和问答历史；本服务只负责 AI 相关的工作：
  - /ingest       解析文档 → 切分 → 向量化 → 存入 Chroma 向量库
  - /ask          检索相关片段 + 调用 DeepSeek 生成答案（一次性返回）
  - /ask/stream   同上，但以 SSE 流式逐字返回
  - /fetch-url    下载网页并保存为 HTML 文件（网页导入的第一步）
  - /documents/{id}/context  取某个文本块及前后文（前端查看原文用）
  - /search       只做向量检索（调试用）
  - /parse        只解析切分、不入库（调试用）
"""
import json
import logging
import os
import threading

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from . import bm25, embeddings, rag, reindex, reranker, retrieval, vectorstore, web_fetch
from .document_loader import split_document

app = FastAPI(title="KB RAG Service")
log = logging.getLogger("uvicorn.error")

# 允许跨域（本服务只在 Docker 内网被 Java 调用，这里放开便于本地调试）
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ---------- 请求体定义（pydantic 会自动校验字段类型） ----------

class ParseReq(BaseModel):
    """文档解析/入库请求"""
    document_id: int   # Java 端数据库中的文档 ID
    file_path: str     # 文件在共享上传目录中的路径
    file_name: str     # 原始文件名，用于回答时标注来源
    kb_id: int = 0     # 所属知识库 ID（检索时按用户权限过滤）


class HistoryItem(BaseModel):
    """一轮历史对话"""
    question: str = ""
    answer: str = ""


class AskReq(BaseModel):
    """问答请求"""
    question: str
    document_id: int | None = None   # 为空表示在全部文档中检索
    history: list[HistoryItem] = []  # 最近几轮对话，用于多轮追问
    # 用户有权限的知识库 ID 列表，由 Java 端根据登录用户计算后传入；None 表示不限制（仅内部调试）
    kb_ids: list[int] | None = None


class KbSyncReq(BaseModel):
    """文档与知识库的对应关系：{文档ID: 知识库ID}"""
    mapping: dict[int, int]


class FetchUrlReq(BaseModel):
    """网页导入请求"""
    url: str


# ---------- 接口 ----------

@app.on_event("startup")
def warmup():
    """
    服务启动时：
      1. 本地模型模式：做一次检索，提前把 bge-m3 加载进内存（云端模式跳过，不依赖网络和 key）；
      2. BM25 索引与 Chroma 对账（缓存中没有的块重新分词）；
      3. 预热本地重排序模型（启用时）。
    """
    if embeddings.EMBEDDING_PROVIDER == "local":
        vectorstore.get_store().similarity_search("预热", k=1)
    vectorstore.sync_bm25()
    reranker.warmup()
    n = vectorstore.get_store()._collection.count()
    if n == 0 and reindex.pick_source(reindex._client(), vectorstore.COLLECTION):
        log.warning("当前向量集合 %s 为空，但存在旧集合：请运行 python -m app.reindex 或 POST /admin/reindex",
                    vectorstore.COLLECTION)


@app.get("/health")
def health():
    """健康检查，docker-compose 用它判断服务是否就绪。"""
    return {"status": "ok"}


@app.post("/parse")
def parse(req: ParseReq):
    """只解析和切分文档，返回块数量和前 3 块预览，不写入向量库（调试用）。"""
    chunks = split_document(req.file_path, req.document_id, req.file_name)
    return {
        "chunk_count": len(chunks),
        "preview": [{"page": c.metadata["page"], "text": c.page_content[:100]} for c in chunks[:3]],
    }


import contextlib as _contextlib
import threading as _threading

# 入库串行锁：INGEST_SERIAL=true（默认）时同一时刻只处理一个文件
_INGEST_LOCK = (_threading.Lock() if os.getenv("INGEST_SERIAL", "true").lower() in ("1", "true", "yes")
                else _contextlib.nullcontext())


@app.post("/ingest")
def ingest(req: ParseReq):
    """解析 → 切分 → 向量化 → 存入 Chroma，返回文本块数量。

    低内存服务器上同时解析多个文件（尤其是 OCR）会让内存叠加，
    所以这里用一把全局锁让入库串行执行（INGEST_SERIAL=false 可关闭）。
    """
    with _INGEST_LOCK:
        chunks = split_document(req.file_path, req.document_id, req.file_name)
        ocr_pages = len({c.metadata["page"] for c in chunks if c.metadata.get("ocr")})
        return {
            "chunk_count": vectorstore.add_chunks(req.document_id, chunks, req.kb_id),
            "ocr_pages": ocr_pages,   # 通过 OCR 识别的页数，前端显示“OCR”标记
        }


@app.post("/kb-sync")
def kb_sync(req: KbSyncReq):
    """批量设置文档所属知识库（文档移动、老数据迁移时由 Java 调用），只改元数据不重新向量化。"""
    updated = {doc_id: vectorstore.set_document_kb(doc_id, kb_id) for doc_id, kb_id in req.mapping.items()}
    return {"updated_chunks": sum(updated.values())}


@app.delete("/documents/{document_id}")
def delete_doc(document_id: int):
    """删除某个文档在向量库中的全部文本块。"""
    vectorstore.delete_document(document_id)
    return {"ok": True}


@app.post("/fetch-url")
def fetch_url(req: FetchUrlReq):
    """下载网页并保存到上传目录，返回 {"title", "file_path", "url"}；失败返回 400 和原因。"""
    try:
        return web_fetch.fetch(req.url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:   # 兜底：未预料的错误也返回可读原因，而不是 500
        logging.getLogger(__name__).exception("fetch-url failed: %s", req.url)
        raise HTTPException(status_code=400, detail=f"网页解析失败（{e.__class__.__name__}）")


@app.get("/documents/{document_id}/context")
def context(document_id: int, chunk_index: int, window: int = 2):
    """返回某个文本块及其前后各 window 块，供前端展示原文上下文。"""
    return vectorstore.get_context(document_id, chunk_index, min(window, 5))


@app.get("/search")
def search(q: str, k: int = 6, document_id: int | None = None, mode: str | None = None):
    """
    检索调试：返回前 k 个文本块及分数明细（不调用大模型）。
    mode：vector（仅向量）/ hybrid（混合）/ rerank（混合 + 重排），不传则用默认模式。
    """
    return [
        {
            "score": score,
            "detail": detail,
            "page": doc.metadata["page"],
            "file_name": doc.metadata["file_name"],
            "kb_id": doc.metadata.get("kb_id"),
            "text": doc.page_content,
        }
        for doc, score, detail in retrieval.retrieve(q, k, document_id, None, mode)
    ]


class EvalReq(BaseModel):
    """评测请求"""
    judge: bool = False        # 是否启用大模型裁判
    with_answer: bool = True   # 是否生成答案（否则只评测检索）


@app.post("/eval/run")
def eval_run(req: EvalReq):
    """在后台线程中运行评测，立即返回；用 /eval/status 查询进度。"""
    from eval import run_eval
    if run_eval.STATUS["running"]:
        return {"started": False, "message": "评测正在进行中"}
    threading.Thread(target=run_eval.run_background, args=(req.judge, req.with_answer), daemon=True).start()
    return {"started": True}


@app.get("/eval/status")
def eval_status():
    """评测进度和最近一次报告（Markdown）。"""
    from eval import run_eval
    report = run_eval.REPORT.read_text(encoding="utf-8") if run_eval.REPORT.exists() else ""
    return {**run_eval.STATUS, "report": report}


@app.get("/stats")
def stats():
    """检索相关配置与索引规模（运维查看用）。"""
    return {
        "chunks_bm25": len(bm25.index),
        "chunks_vector": vectorstore.get_store()._collection.count(),
        "retrieval_mode": retrieval.DEFAULT_MODE,
        "embedding_provider": embeddings.EMBEDDING_PROVIDER,
        "embedding_model": (embeddings.DASHSCOPE_EMBEDDING_MODEL if embeddings.EMBEDDING_PROVIDER == "dashscope"
                            else embeddings.EMBEDDING_MODEL),
        "collection": vectorstore.COLLECTION,
        "rerank_provider": reranker.RERANK_PROVIDER,
        "rerank_enabled": reranker.enabled(),
        "rerank_model": reranker.RERANK_MODEL,
        "rerank_threshold": retrieval.RERANK_THRESHOLD,
    }


class ReindexReq(BaseModel):
    """重建索引请求"""
    source: str | None = None   # 源集合名，默认自动选择
    force: bool = False         # 已存在的块也重新计算


@app.post("/admin/reindex")
def admin_reindex(req: ReindexReq):
    """切换向量模型后，在后台用新模型重算全部文本块的向量；GET /admin/reindex 查看进度。"""
    if reindex.STATUS["running"]:
        return {"started": False, "message": "重建索引正在进行中"}

    def job():
        try:
            reindex.run(req.source, req.force)
        except Exception as e:
            log.error("重建索引失败：%s", e)

    threading.Thread(target=job, daemon=True).start()
    return {"started": True, "target": vectorstore.COLLECTION}


@app.get("/admin/reindex")
def admin_reindex_status():
    return reindex.STATUS


@app.post("/ask")
def ask(req: AskReq):
    """普通问答：返回 {"answer": "...", "sources": [...]}。"""
    return rag.answer(req.question, req.document_id, [h.model_dump() for h in req.history], kb_ids=req.kb_ids)


@app.post("/ask/stream")
def ask_stream(req: AskReq):
    """
    流式问答（SSE 格式）。每个事件是一行 "data: {json}"，后跟一个空行。
    事件类型：rewrite（多轮时改写后的问题）→ sources（引用片段）→ 多个 delta（答案片段）→ done（结束）；出错时发送 error。
    """
    def gen():
        try:
            history = [h.model_dump() for h in req.history]
            for event in rag.answer_stream(req.question, req.document_id, history, kb_ids=req.kb_ids):
                yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except Exception as e:
            # 出错也通过事件通知前端，而不是直接断开连接
            yield f"data: {json.dumps({'type': 'error', 'data': str(e)}, ensure_ascii=False)}\n\n"
        # 无论成功失败，最后都发送结束事件
        yield 'data: {"type": "done"}\n\n'

    return StreamingResponse(gen(), media_type="text/event-stream")
