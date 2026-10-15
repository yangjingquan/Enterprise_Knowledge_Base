"""意图路由（设计文档第 8 节）：文档问答 doc / 数据分析 data / 两者混合 mixed"""
import re

ROUTE_SYSTEM = """判断用户问题应该用哪种方式回答，只输出一个词：
doc  —— 查询制度、手册、说明、流程等文档内容；
data —— 需要对数据表做统计、汇总、筛选、排名、趋势、计算（多少、合计、占比、同比、哪天/哪个最高…）；
mixed —— 同时需要数据统计结果和制度/规定/标准文档，例如"9月各渠道ROI是否达到制度规定的标准""实际差旅费有没有超过报销标准"。
如果问题涉及的指标或维度出现在下面的数据表里，倾向 data。
<data>
{tables}
</data>"""

_DATA_HINT = re.compile(r"(统计|汇总|合计|总计|求和|平均|占比|同比|环比|趋势|排名|top\s*\d|前\s*\d+\s*(名|个)|最高|最低|最多|最少|多少|几(个|笔|单)|"
                        r"\d{1,2}\s*月\s*\d{1,2}\s*[日号]?\s*(到|至|-|~)|按.{1,8}(分组|拆|统计|汇总)|各.{1,6}的)", re.I)


_DOC_HINT = re.compile(r"(制度|规定|规范|标准|政策|手册|流程|要求|办法|条例|是否(符合|达到|达标|超过|超标))")


def route(question: str, datasets: list, mode: str = "auto") -> str:
    if mode in ("doc", "data", "mixed"):
        return mode
    if not datasets:
        return "doc"
    names = []
    for d in datasets:
        for t in d.get("tables", []):
            cols = "、".join(str(c.get("display_name"))[:20] for c in t["columns"][:20])
            names.append(f"{str(d.get('name'))[:40]} / {str(t.get('display_name'))[:40]}：{cols}")
    from .. import rag
    try:
        resp = rag.client().chat.completions.create(
            model=rag.MODEL, temperature=0, max_tokens=3,
            messages=[{"role": "system", "content": ROUTE_SYSTEM.format(tables="\n".join(names[:30]))},
                      {"role": "user", "content": question}])
        out = (resp.choices[0].message.content or "").strip().lower()
        if out.startswith("mix"):
            return "mixed"
        if out.startswith("data"):
            return "data"
        if out.startswith("doc"):
            return "doc"
    except Exception:
        pass
    data, doc = bool(_DATA_HINT.search(question)), bool(_DOC_HINT.search(question))
    return "mixed" if data and doc else "data" if data else "doc"
