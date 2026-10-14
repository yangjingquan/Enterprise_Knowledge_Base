"""多轮对话逻辑单元测试：用假的大模型客户端和假的向量库，不访问网络。"""
from types import SimpleNamespace

from langchain_core.documents import Document

from app import rag


class FakeClient:
    """记录收到的请求，按预设内容返回，模拟 OpenAI SDK 的 chat.completions.create。"""

    def __init__(self, reply="满20年的员工年假共17天。", fail=False):
        self.calls = []
        self.reply = reply
        self.fail = fail
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, **kw):
        self.calls.append(kw)
        if self.fail:
            raise RuntimeError("network down")
        msg = SimpleNamespace(content=self.reply)
        return SimpleNamespace(choices=[SimpleNamespace(message=msg)])


def _use(monkeypatch, fake, hits=None):
    monkeypatch.setattr(rag, "client", lambda: fake)
    searched = []

    def fake_retrieve(q, k, doc_id=None, kb_ids=None, mode=None):
        searched.append(q)
        # 模拟阈值过滤：分数低于 0.5 的不返回
        return [(d, s, {}) for d, s in (hits or []) if s >= 0.5]
    monkeypatch.setattr(rag.retrieval, "retrieve", fake_retrieve)
    return searched


HISTORY = [{"question": "工作满10年有几天年假？", "answer": "满10年不满20年共12天。"}]


def test_no_history_skips_rewrite(monkeypatch):
    fake = FakeClient()
    assert rag.rewrite_question("工作满10年有几天年假？", []) == "工作满10年有几天年假？"
    _use(monkeypatch, fake)
    assert rag.rewrite_question("x", []) == "x"
    assert fake.calls == []


def test_rewrite_with_history(monkeypatch):
    fake = FakeClient(reply="“工作满20年有几天年假？”")
    _use(monkeypatch, fake)
    out = rag.rewrite_question("那满20年呢？", HISTORY)
    assert out == "工作满20年有几天年假？"
    prompt = fake.calls[0]["messages"][0]["content"]
    assert "工作满10年有几天年假" in prompt and "那满20年呢" in prompt


def test_rewrite_failure_falls_back(monkeypatch):
    _use(monkeypatch, FakeClient(fail=True))
    assert rag.rewrite_question("那满20年呢？", HISTORY) == "那满20年呢？"


def test_rewrite_rejects_long_answer(monkeypatch):
    _use(monkeypatch, FakeClient(reply="根据员工手册" * 100))
    assert rag.rewrite_question("那满20年呢？", HISTORY) == "那满20年呢？"


def test_normalize_history_keeps_last_3_and_truncates():
    hist = [{"question": f"q{i}", "answer": "a" * 1000} for i in range(5)] + [{"question": "空", "answer": ""}]
    out = rag.normalize_history(hist)
    assert [h["question"] for h in out] == ["q2", "q3", "q4"]
    assert len(out[0]["answer"]) == rag.HISTORY_ANSWER_CHARS


def test_answer_stream_uses_rewritten_query_and_history(monkeypatch):
    doc = Document(page_content="满20年 15天 2天 17天", metadata={
        "file_name": "员工手册.pdf", "document_id": 1, "page": 5, "chunk_index": 12})

    class StreamClient(FakeClient):
        def create(self, **kw):
            self.calls.append(kw)
            if kw.get("stream"):
                d = SimpleNamespace(delta=SimpleNamespace(content="17天"))
                return iter([SimpleNamespace(choices=[d])])
            return super().create(**kw)

    fake = StreamClient(reply="工作满20年有几天年假？")
    searched = _use(monkeypatch, fake, hits=[(doc, 0.8)])
    events = list(rag.answer_stream("那满20年呢？", None, HISTORY))
    assert events[0] == {"type": "rewrite", "data": "工作满20年有几天年假？"}
    assert searched == ["工作满20年有几天年假？"]          # 用改写后的问题检索
    assert events[1]["type"] == "sources" and events[1]["data"][0]["chunk_index"] == 12
    assert events[-1] == {"type": "delta", "data": "17天"}
    msgs = fake.calls[-1]["messages"]
    roles = [m["role"] for m in msgs]
    assert roles == ["system", "user", "assistant", "user"]   # 历史以多轮消息形式带入
    assert "那满20年呢" in msgs[-1]["content"] and "[1]" in msgs[-1]["content"]


def test_no_sources_returns_fixed_answer(monkeypatch):
    fake = FakeClient()
    _use(monkeypatch, fake, hits=[(Document(page_content="x", metadata={
        "file_name": "a", "document_id": 1, "page": 1}), 0.3)])
    events = list(rag.answer_stream("今天天气如何？"))
    assert events[-1]["data"] == rag.NO_ANSWER
    assert fake.calls == []    # 不调用大模型
