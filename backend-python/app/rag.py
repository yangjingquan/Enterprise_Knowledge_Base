"""
RAG（检索增强生成）问答，支持多轮对话。

流程：
  1. 有对话历史时，先让大模型把追问（如“那满20年呢？”）改写成独立完整的问题，
     没有历史则直接用原问题，省一次大模型调用；
  2. 用改写后的问题做混合检索（向量 + BM25 + 重排序，见 retrieval.py），
     只在用户有权限的知识库中检索；
  3. 过滤掉相关度低于阈值的块（太不相关的不给大模型，减少胡编）；
  4. 把对话历史 + 文本块 + 当前问题拼成消息，要求大模型只根据参考内容回答；
  5. 调用 DeepSeek 生成答案，并把引用的片段作为“来源”一起返回。
"""
import logging
import os

from openai import OpenAI

from . import retrieval

log = logging.getLogger(__name__)

# 每次检索的片段数：多格式文档混在一起时 4 个偏少，6 个能兼顾召回率和提示词长度
TOP_K = int(os.getenv("TOP_K", "6"))
# DeepSeek 模型名称
MODEL = os.getenv("LLM_MODEL", "deepseek-chat")
# 最多带入最近几轮对话（一问一答为一轮），太多会稀释注意力、增加费用
MAX_HISTORY_ROUNDS = 3
# 历史回答截断长度：历史只用来理解上下文，不需要完整答案
HISTORY_ANSWER_CHARS = 600
# 检索不到相关内容时的固定回复（不调用大模型，直接返回）
NO_ANSWER = "根据现有文档无法回答该问题。"

# 系统提示词：规定助手的角色和回答规则
SYSTEM_PROMPT = """你是一个专业的企业知识库助手。请严格根据用户消息中的「参考内容」回答问题。
规则：
1. 只使用参考内容中的信息；如果参考内容中没有相关信息，请明确回答「根据现有文档无法回答该问题」。
2. 之前的对话只用于理解当前问题指代的是什么，不能作为事实依据。
3. 回答要专业、简洁，尽量引用原文关键句子，并用 [1][2] 这样的编号标注出处。
4. 用中文回答。"""

# 用户消息模板：{context} 填入检索到的片段，{question} 填入用户问题
USER_PROMPT = """参考内容：
{context}

用户问题：{question}"""

# 问题改写提示词：把依赖上下文的追问改写成可以独立检索的完整问题
REWRITE_PROMPT = """下面是用户与企业知识库助手之前的对话，以及用户的最新问题。
最新问题可能省略了主语或使用了“那”“它”“这个”等指代。请结合对话，把最新问题改写成一个
意思完整、不依赖上下文也能看懂的独立问题。
要求：只输出改写后的问题本身，不要回答问题，不要解释；如果最新问题本来就是完整的，原样输出。

对话：
{history}

最新问题：{question}

改写后的问题："""

_client = None


def client():
    """
    创建并缓存 DeepSeek 客户端。
    DeepSeek 的接口兼容 OpenAI 格式，所以直接用 openai SDK，只需把 base_url 指向 DeepSeek。
    API Key 从环境变量 DEEPSEEK_API_KEY 读取，不写在代码里。
    """
    global _client
    if _client is None:
        _client = OpenAI(
            api_key=os.environ["DEEPSEEK_API_KEY"],
            base_url=os.getenv("LLM_BASE_URL", "https://api.deepseek.com"),
            timeout=60,
        )
    return _client


def normalize_history(history) -> list:
    """
    清洗前端传来的对话历史：只保留问题和答案都非空的轮次，取最近 MAX_HISTORY_ROUNDS 轮，
    答案过长时截断。返回 [{"question": ..., "answer": ...}]。
    """
    rounds = []
    for h in history or []:
        q = str(h.get("question") or "").strip()
        a = str(h.get("answer") or "").strip()
        if q and a:
            rounds.append({"question": q, "answer": a[:HISTORY_ANSWER_CHARS]})
    return rounds[-MAX_HISTORY_ROUNDS:]


def _history_text(history: list) -> str:
    """把历史拼成“用户：… / 助手：…”的纯文本，给改写提示词使用。"""
    return "\n".join(f"用户：{h['question']}\n助手：{h['answer']}" for h in history)


def rewrite_question(question: str, history: list) -> str:
    """
    把追问改写成独立问题。没有历史时直接返回原问题（不调用大模型）。
    改写失败（网络错误等）或结果异常时退回原问题，保证问答不会因为改写而中断。
    """
    if not history:
        return question
    try:
        resp = client().chat.completions.create(
            model=MODEL,
            temperature=0,     # 改写要稳定，不需要创造性
            max_tokens=200,
            messages=[{
                "role": "user",
                "content": REWRITE_PROMPT.format(history=_history_text(history), question=question),
            }],
        )
        text = (resp.choices[0].message.content or "").strip().strip("“”\"").strip()
    except Exception:
        log.exception("问题改写失败，使用原问题")
        return question
    # 防御：模型偶尔会输出空串或长篇回答，这种情况不采用改写结果
    if not text or len(text) > max(200, len(question) * 6):
        return question
    return text.splitlines()[0].strip()


def _retrieve(question: str, document_id: int | None, k: int, kb_ids=None, mode=None):
    """检索并过滤，返回前端展示用的来源列表（index 为引用编号 [1][2]...）。"""
    sources = []
    for i, (doc, score, detail) in enumerate(retrieval.retrieve(question, k, document_id, kb_ids, mode)):
        meta = doc.metadata
        sources.append({
            "index": i + 1,
            "score": score,
            "file_name": meta["file_name"],
            "document_id": meta["document_id"],
            "kb_id": meta.get("kb_id"),
            "page": meta["page"],
            "chunk_index": meta.get("chunk_index"),
            # Excel 的工作表名、PPT 的幻灯片标题（其他格式为空）
            "sheet_name": meta.get("sheet_name"),
            "slide_title": meta.get("slide_title"),
            # Word 文档的章节路径（按标题识别，新上传的 Word 才有）
            "section": meta.get("section"),
            # 是否来自 OCR 识别（扫描件）
            "ocr": bool(meta.get("ocr")),
            # 各路检索的分数明细：向量相似度、BM25 名次、重排分数
            "detail": detail,
            "text": doc.page_content,
        })
    return sources


def _location(x: dict) -> str:
    """来源位置描述：Excel 显示工作表名，Word 显示章节，PPT 显示第几张幻灯片，其他显示页码。"""
    if x.get("sheet_name"):
        return f"工作表「{x['sheet_name']}」"
    if x.get("section"):
        return f"章节「{x['section']}」"
    name = x["file_name"].lower()
    if name.endswith(".pptx"):
        return f"第{x['page']}张幻灯片"
    return f"第{x['page']}页"


def _build_messages(question: str, sources: list, history: list):
    """
    组装大模型的 messages：系统提示词 → 历史对话（多轮）→ 本轮参考内容 + 问题。
    历史放在真实的 user / assistant 角色里，模型更容易理解对话脉络。
    """
    context = "\n\n".join(
        f"[{x['index']}]（{x['file_name']} {_location(x)}）\n{x['text']}" for x in sources
    )
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for h in history:
        messages.append({"role": "user", "content": h["question"]})
        messages.append({"role": "assistant", "content": h["answer"]})
    messages.append({"role": "user", "content": USER_PROMPT.format(context=context, question=question)})
    return messages


def answer(question: str, document_id: int | None = None, history=None, k: int = TOP_K,
           kb_ids=None, mode=None):
    """普通问答：等大模型生成完整答案后一次性返回。"""
    history = normalize_history(history)
    standalone = rewrite_question(question, history)
    sources = _retrieve(standalone, document_id, k, kb_ids, mode)
    if not sources:
        return {"answer": NO_ANSWER, "sources": [], "rewritten_question": standalone}

    resp = client().chat.completions.create(
        model=MODEL,
        temperature=0.2,   # 温度低一些，回答更稳定、更贴近原文
        messages=_build_messages(question, sources, history),
    )
    return {
        "answer": resp.choices[0].message.content.strip(),
        "sources": sources,
        "rewritten_question": standalone,
    }


def answer_stream(question: str, document_id: int | None = None, history=None, k: int = TOP_K,
                  kb_ids=None, mode=None):
    """
    流式问答（生成器）：
      rewrite（改写后的问题，仅多轮时）→ sources（引用片段）→ 多个 delta（答案文字片段）。
    """
    history = normalize_history(history)
    standalone = rewrite_question(question, history)
    if standalone != question:
        yield {"type": "rewrite", "data": standalone}

    sources = _retrieve(standalone, document_id, k, kb_ids, mode)
    yield {"type": "sources", "data": sources}

    if not sources:
        yield {"type": "delta", "data": NO_ANSWER}
        return

    # stream=True：DeepSeek 每生成一小段文字就返回一次
    stream = client().chat.completions.create(
        model=MODEL,
        temperature=0.2,
        stream=True,
        messages=_build_messages(question, sources, history),
    )
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta.content:
            yield {"type": "delta", "data": chunk.choices[0].delta.content}
