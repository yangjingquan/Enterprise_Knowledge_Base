"""
重排序（Rerank）：给“问题-片段”逐对打分，比向量相似度排序更准。

RERANK_PROVIDER 选择实现：
  - dashscope：阿里云百炼 gte-rerank-v2（云端，不占本机内存，默认）
  - local    ：本地交叉编码器 BAAI/bge-reranker-v2-m3（约 2.3GB 内存，需构建时 WITH_LOCAL_MODELS=true）
  - none     ：不重排，检索退回“向量 + BM25”混合模式
兼容旧配置：未设置 RERANK_PROVIDER 时，RERANK_ENABLED=false 视为 none；
否则跟随 EMBEDDING_PROVIDER（dashscope → dashscope，local → local）。

只对两路检索合并后的前 N 个候选（默认 15 个）做重排。
云端重排调用失败时 score() 抛异常，retrieval 会自动退回 hybrid 模式，问答不中断。
"""
import logging
import os
import threading
from functools import lru_cache

import requests

log = logging.getLogger(__name__)


def _provider():
    p = os.getenv("RERANK_PROVIDER", "").lower()
    if p:
        return p
    if os.getenv("RERANK_ENABLED", "true").lower() not in ("1", "true", "yes"):
        return "none"
    return "local" if os.getenv("EMBEDDING_PROVIDER", "dashscope").lower() == "local" else "dashscope"


RERANK_PROVIDER = _provider()
RERANK_ENABLED = RERANK_PROVIDER in ("local", "dashscope")
RERANK_MODEL = (os.getenv("DASHSCOPE_RERANK_MODEL", "gte-rerank-v2") if RERANK_PROVIDER == "dashscope"
                else os.getenv("RERANK_MODEL", "BAAI/bge-reranker-v2-m3"))
RERANK_MAX_LENGTH = int(os.getenv("RERANK_MAX_LENGTH", "512"))
DASHSCOPE_RERANK_URL = os.getenv(
    "DASHSCOPE_RERANK_URL",
    "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank")
RERANK_TIMEOUT = float(os.getenv("RERANK_TIMEOUT", "15"))
RERANK_RETRIES = int(os.getenv("RERANK_RETRIES", "2"))

# 本地交叉编码器同时只跑一个请求：CPU 上并发推理会互相抢线程，反而更慢
_LOCK = threading.Lock()
_session = requests.Session()


@lru_cache
def _model():
    """加载本地模型（只加载一次）。"""
    from sentence_transformers import CrossEncoder
    log.info("加载重排序模型 %s", RERANK_MODEL)
    return CrossEncoder(RERANK_MODEL, max_length=RERANK_MAX_LENGTH, device="cpu")


def enabled() -> bool:
    return RERANK_ENABLED


def warmup():
    """启动时预热本地模型；云端模式无需预热（也避免没有 key 时启动失败）。"""
    if RERANK_PROVIDER == "local":
        score("预热", ["预热"])


def score(question: str, texts: list[str]) -> list[float]:
    """返回每个片段与问题的相关度（0~1），顺序与 texts 一致。"""
    if not texts:
        return []
    if RERANK_PROVIDER == "dashscope":
        return _score_dashscope(question, texts)
    import torch
    with _LOCK:
        scores = _model().predict(
            [(question, t) for t in texts],
            batch_size=8,
            activation_fn=torch.nn.Sigmoid(),
            show_progress_bar=False,
        )
    return [float(s) for s in scores]


def _score_dashscope(question, texts):
    import time
    key = os.getenv("DASHSCOPE_API_KEY", "")
    if not key:
        raise RuntimeError("未配置 DASHSCOPE_API_KEY，无法调用百炼重排接口")
    body = {"model": RERANK_MODEL,
            "input": {"query": question, "documents": [t[:4000] for t in texts]},
            "parameters": {"return_documents": False, "top_n": len(texts)}}
    last = None
    for attempt in range(RERANK_RETRIES + 1):
        try:
            r = _session.post(DASHSCOPE_RERANK_URL, json=body, timeout=RERANK_TIMEOUT,
                              headers={"Authorization": f"Bearer {key}"})
            if r.status_code == 429 or r.status_code >= 500:
                raise requests.ConnectionError(f"HTTP {r.status_code}")
            if r.status_code >= 400:
                raise RuntimeError(f"百炼重排接口返回 {r.status_code}: {r.text[:200]}")
            out = [0.0] * len(texts)
            for item in r.json()["output"]["results"]:
                out[item["index"]] = float(item["relevance_score"])
            return out
        except (requests.ConnectionError, requests.Timeout) as e:
            last = e
            if attempt < RERANK_RETRIES:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"百炼重排接口多次重试仍失败：{last}")
