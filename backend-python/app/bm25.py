"""
BM25 关键词检索。

为什么需要：向量检索擅长“意思相近”，但对设备编号、故障代码、型号、金额这类“精确字符串”
（如 E-03、LF-10、8200 小时）不敏感，常常排不到前面。BM25 按词频统计打分，正好补上这一块。

实现要点：
  - 分词：jieba 搜索引擎模式 + 正则单独提取编号 / 数字（jieba 会把 “E-03” 切成 “E”“-”“03”）；
  - 索引与 Chroma 保持一致：文本和元数据都以 Chroma 为准，启动时从 Chroma 全量对账；
    新增 / 删除文档时同步更新，保证两路检索看到的是同一批文本块；
  - 持久化：分词结果缓存到 CHROMA_DIR/bm25_tokens.json，重启时只对新增的块分词，速度快；
  - 过滤：与向量检索一样支持按 document_id、kb_id 列表过滤。
"""
import json
import logging
import os
import re
import threading
import unicodedata
from pathlib import Path

import jieba
import numpy as np
from rank_bm25 import BM25Okapi

log = logging.getLogger(__name__)
jieba.setLogLevel(logging.WARNING)

CHROMA_DIR = os.getenv("CHROMA_DIR", "../chroma_db")
CACHE_FILE = Path(CHROMA_DIR) / "bm25_tokens.json"

# 编号 / 型号：字母开头、可带连字符的字母数字串，如 E-03、LF-10、SK-850B、SP-SK-003、A06B-6114-K504。
# 连字符后允许一个空格：PDF 表格里的编号常被折行成“E- 03”
_CODE_RE = re.compile(r"[a-z][a-z0-9]*(?:- ?[a-z0-9]+)+|[a-z]+\d+[a-z0-9]*")
# 数字（含小数），如 8200、0.01、30
_NUM_RE = re.compile(r"\d+(?:\.\d+)?")
# 停用词：常见虚词和标点，不参与打分
_STOP = set("的 了 是 在 和 与 及 或 等 为 对 把 被 有 这 那 哪 吗 呢 吧 啊 什么 怎么 如何 多少 几 个 一 请 问 我 你 他 们 要 需要 应该 可以 进行".split())


def tokenize(text: str) -> list[str]:
    """
    分词：先 NFKC 规范化并转小写，再提取编号和数字作为整体词，最后用 jieba 切中文。
    编号同时保留整体（“lf-10”）和去掉连字符的形式（“lf10”），用户写法不同也能匹配。
    """
    t = unicodedata.normalize("NFKC", text or "").lower()
    tokens = []
    for code in _CODE_RE.findall(t):
        code = code.replace(" ", "")
        tokens.append(code)
        tokens.append(code.replace("-", ""))
    tokens += _NUM_RE.findall(t)
    for w in jieba.cut_for_search(t):
        w = w.strip()
        # 跳过空白、单个标点和停用词
        if not w or w in _STOP or not re.search(r"[\w\u4e00-\u9fff]", w):
            continue
        tokens.append(w)
    return tokens


class BM25Index:
    """线程安全的 BM25 索引。ids / tokens / metas 三个列表下标一一对应。"""

    def __init__(self):
        self._lock = threading.RLock()
        self.ids: list[str] = []
        self.tokens: list[list[str]] = []
        self.metas: list[dict] = []
        self.texts: list[str] = []
        self._bm25 = None

    # ---------- 构建与同步 ----------

    def sync_from_chroma(self, collection):
        """
        与 Chroma 全量对账：Chroma 里有、缓存里没有的块重新分词；Chroma 里已删除的块从索引去掉。
        collection 为 chromadb 的 Collection 对象。
        """
        data = collection.get(include=["documents", "metadatas"])
        cache = self._load_cache()
        ids, tokens, metas, texts = [], [], [], []
        new_count = 0
        for cid, text, meta in zip(data["ids"], data["documents"], data["metadatas"]):
            tok = cache.get(cid)
            if tok is None:
                tok = tokenize(text)
                new_count += 1
            ids.append(cid)
            tokens.append(tok)
            metas.append(meta or {})
            texts.append(text)
        with self._lock:
            self.ids, self.tokens, self.metas, self.texts = ids, tokens, metas, texts
            self._rebuild()
            self._save_cache()
        log.info("BM25 索引就绪：%d 块（新分词 %d 块）", len(ids), new_count)

    def add(self, ids, texts, metas):
        """新增文本块（同 ID 已存在则替换）。"""
        with self._lock:
            self._remove_where(lambda i: self.ids[i] in set(ids))
            for cid, text, meta in zip(ids, texts, metas):
                self.ids.append(cid)
                self.tokens.append(tokenize(text))
                self.metas.append(dict(meta))
                self.texts.append(text)
            self._rebuild()
            self._save_cache()

    def delete_document(self, document_id: int):
        """删除某文档的全部块。"""
        with self._lock:
            self._remove_where(lambda i: self.metas[i].get("document_id") == document_id)
            self._rebuild()
            self._save_cache()

    def update_kb(self, document_id: int, kb_id: int):
        """文档换了知识库时，同步更新元数据中的 kb_id。"""
        with self._lock:
            for m in self.metas:
                if m.get("document_id") == document_id:
                    m["kb_id"] = kb_id

    # ---------- 检索 ----------

    def search(self, query: str, k: int = 20, document_id=None, kb_ids=None):
        """
        返回 [(id, 文本, 元数据, BM25 分数)]，按分数从高到低，只返回分数 > 0 的块。
        kb_ids 为 None 表示不限制知识库；为空列表表示没有任何权限，直接返回空。
        """
        if kb_ids is not None and len(kb_ids) == 0:
            return []
        q = tokenize(query)
        with self._lock:
            if not self._bm25 or not q:
                return []
            scores = self._bm25.get_scores(q)
            allowed_kb = set(kb_ids) if kb_ids is not None else None
            order = np.argsort(-scores)
            out = []
            for i in order:
                if scores[i] <= 0 or len(out) >= k:
                    break
                m = self.metas[i]
                if document_id and m.get("document_id") != document_id:
                    continue
                if allowed_kb is not None and m.get("kb_id") not in allowed_kb:
                    continue
                out.append((self.ids[i], self.texts[i], m, float(scores[i])))
            return out

    def __len__(self):
        return len(self.ids)

    # ---------- 内部方法 ----------

    def _remove_where(self, cond):
        keep = [i for i in range(len(self.ids)) if not cond(i)]
        self.ids = [self.ids[i] for i in keep]
        self.tokens = [self.tokens[i] for i in keep]
        self.metas = [self.metas[i] for i in keep]
        self.texts = [self.texts[i] for i in keep]

    def _rebuild(self):
        # BM25Okapi 需要非空语料；空文本块用占位词，避免除零
        self._bm25 = BM25Okapi([t or ["_"] for t in self.tokens]) if self.tokens else None

    def _load_cache(self) -> dict:
        try:
            return json.loads(CACHE_FILE.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_cache(self):
        try:
            CACHE_FILE.parent.mkdir(parents=True, exist_ok=True)
            tmp = CACHE_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps(dict(zip(self.ids, self.tokens)), ensure_ascii=False), encoding="utf-8")
            tmp.replace(CACHE_FILE)   # 先写临时文件再替换，避免写一半时崩溃导致缓存损坏
        except Exception:
            log.exception("BM25 缓存写入失败（不影响检索）")


# 全局单例
index = BM25Index()
