"""
向量模型（Embedding）提供方。

EMBEDDING_PROVIDER 决定文本如何变成向量：
  - dashscope（默认）：调用阿里云百炼 text-embedding-v3/v4（OpenAI 兼容接口），
    本机不加载模型，Python 容器内存约 300~500MB，适合 4GB 内存服务器；
  - local：本机加载 BAAI/bge-m3（需构建时 WITH_LOCAL_MODELS=true，运行约占 2.3GB 内存）。

不同模型产生的向量不能混用，所以每个模型使用独立的 Chroma 集合（见 collection_name()）。
切换模型后需运行一次重建索引：python -m app.reindex 或 POST /admin/reindex。
"""
import logging
import os
import random
import time

import requests
from langchain_core.embeddings import Embeddings

log = logging.getLogger(__name__)

EMBEDDING_PROVIDER = os.getenv("EMBEDDING_PROVIDER", "dashscope").lower()
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")   # local 模式使用
DASHSCOPE_BASE_URL = os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
DASHSCOPE_EMBEDDING_MODEL = os.getenv("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v3")
EMBEDDING_DIM = int(os.getenv("EMBEDDING_DIM", "1024"))
# 百炼 text-embedding-v3 单次最多 10 条（v4 也按 10 条处理，最稳妥）
EMBEDDING_BATCH = int(os.getenv("EMBEDDING_BATCH", "10"))
EMBEDDING_TIMEOUT = float(os.getenv("EMBEDDING_TIMEOUT", "30"))
EMBEDDING_RETRIES = int(os.getenv("EMBEDDING_RETRIES", "4"))
MAX_CHARS = 8000   # 单条文本上限（v3 约 8192 token），超长截断防止整批报错


class DashScopeEmbeddings(Embeddings):
    """百炼 OpenAI 兼容 /embeddings 接口：分批、超时、指数退避重试。"""

    def __init__(self, api_key=None, base_url=DASHSCOPE_BASE_URL, model=DASHSCOPE_EMBEDDING_MODEL,
                 dim=EMBEDDING_DIM, batch=EMBEDDING_BATCH, timeout=EMBEDDING_TIMEOUT, retries=EMBEDDING_RETRIES):
        self.api_key = api_key if api_key is not None else os.getenv("DASHSCOPE_API_KEY", "")
        self.url = base_url.rstrip("/") + "/embeddings"
        self.model, self.dim, self.batch = model, dim, max(1, batch)
        self.timeout, self.retries = timeout, retries
        self.session = requests.Session()

    def _post(self, texts):
        if not self.api_key:
            raise RuntimeError("未配置 DASHSCOPE_API_KEY，无法调用百炼向量接口")
        body = {"model": self.model, "input": texts, "dimensions": self.dim, "encoding_format": "float"}
        headers = {"Authorization": f"Bearer {self.api_key}"}
        last = None
        for attempt in range(self.retries + 1):
            try:
                r = self.session.post(self.url, json=body, headers=headers, timeout=self.timeout)
                # 429 限流和 5xx 可重试；其他 4xx（如 key 错误、参数错误）直接报错
                if r.status_code == 429 or r.status_code >= 500:
                    raise _Retryable(f"HTTP {r.status_code}")
                if r.status_code >= 400:
                    raise RuntimeError(f"百炼向量接口返回 {r.status_code}: {r.text[:200]}")
                data = sorted(r.json()["data"], key=lambda x: x["index"])
                return [d["embedding"] for d in data]
            except (_Retryable, requests.ConnectionError, requests.Timeout) as e:
                last = e
                if attempt < self.retries:
                    wait = min(2 ** attempt, 16) + random.random() * 0.5
                    log.warning("百炼向量接口失败（%s），%.1f 秒后重试", e, wait)
                    time.sleep(wait)
        raise RuntimeError(f"百炼向量接口多次重试仍失败：{last}")

    def embed_documents(self, texts):
        texts = [(t or " ")[:MAX_CHARS] for t in texts]
        out = []
        for i in range(0, len(texts), self.batch):
            out.extend(self._post(texts[i:i + self.batch]))
        return out

    def embed_query(self, text):
        return self.embed_documents([text])[0]


class _Retryable(Exception):
    pass


def get_embeddings() -> Embeddings:
    if EMBEDDING_PROVIDER == "local":
        from langchain_huggingface import HuggingFaceEmbeddings   # 延迟导入：slim 镜像没有 torch
        return HuggingFaceEmbeddings(model_name=EMBEDDING_MODEL, encode_kwargs={"normalize_embeddings": True})
    if EMBEDDING_PROVIDER == "dashscope":
        return DashScopeEmbeddings()
    raise ValueError(f"未知 EMBEDDING_PROVIDER={EMBEDDING_PROVIDER}")


def collection_name() -> str:
    """
    每个向量模型一个集合，避免维度/语义不一致。
    local + bge-m3 沿用老集合 kb_docs（升级前的数据无需重建）；
    dashscope 形如 kb_docs_dashscope_v3_1024。可用 CHROMA_COLLECTION 强制指定。
    """
    if os.getenv("CHROMA_COLLECTION"):
        return os.environ["CHROMA_COLLECTION"]
    if EMBEDDING_PROVIDER == "local":
        return "kb_docs" if EMBEDDING_MODEL == "BAAI/bge-m3" else "kb_docs_local_" + _slug(EMBEDDING_MODEL)
    return f"kb_docs_dashscope_{_slug(DASHSCOPE_EMBEDDING_MODEL.replace('text-embedding-', ''))}_{EMBEDDING_DIM}"


def _slug(s):
    return "".join(c if c.isalnum() else "_" for c in s.lower()).strip("_")
