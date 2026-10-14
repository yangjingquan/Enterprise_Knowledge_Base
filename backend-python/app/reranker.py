"""
重排序（Rerank）：用交叉编码器 BAAI/bge-reranker-v2-m3 给“问题-片段”逐对打分。

向量检索和 BM25 都是把问题、片段分别编码后再比较，速度快但不够精细；
交叉编码器把问题和片段拼在一起送进模型，能看到两者之间逐字的对应关系，排序更准，
但每个候选都要跑一次模型，所以只对两路检索合并后的前 N 个候选（默认 15 个）做重排。

配置（环境变量）：
  RERANK_ENABLED   是否启用，默认 true；内存紧张（如 8GB 内存的电脑）可设为 false
  RERANK_MODEL     模型名，默认 BAAI/bge-reranker-v2-m3（约 2.2GB，运行时占内存约 2.3GB）
  RERANK_MAX_LENGTH 每对文本的最大长度（token），默认 512
"""
import logging
import os
import threading
from functools import lru_cache

log = logging.getLogger(__name__)

RERANK_ENABLED = os.getenv("RERANK_ENABLED", "true").lower() in ("1", "true", "yes")
RERANK_MODEL = os.getenv("RERANK_MODEL", "BAAI/bge-reranker-v2-m3")
RERANK_MAX_LENGTH = int(os.getenv("RERANK_MAX_LENGTH", "512"))

# 交叉编码器同时只跑一个请求：CPU 上并发推理会互相抢线程，反而更慢
_LOCK = threading.Lock()


@lru_cache
def _model():
    """加载模型（只加载一次）。输出经 sigmoid 映射到 0~1，越大越相关。"""
    from sentence_transformers import CrossEncoder
    log.info("加载重排序模型 %s", RERANK_MODEL)
    return CrossEncoder(RERANK_MODEL, max_length=RERANK_MAX_LENGTH, device="cpu")


def enabled() -> bool:
    return RERANK_ENABLED


def warmup():
    """启动时预热，避免第一个问题等待模型加载。"""
    if RERANK_ENABLED:
        score("预热", ["预热"])


def score(question: str, texts: list[str]) -> list[float]:
    """返回每个片段与问题的相关度（0~1）。"""
    if not texts:
        return []
    import torch
    with _LOCK:
        scores = _model().predict(
            [(question, t) for t in texts],
            batch_size=8,
            activation_fn=torch.nn.Sigmoid(),
            show_progress_bar=False,
        )
    return [float(s) for s in scores]
