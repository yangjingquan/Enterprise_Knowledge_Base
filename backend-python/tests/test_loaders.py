"""文档解析单元测试：覆盖 PDF / Excel / PPT / 网页 / TXT，使用 demo-data 中的示例文件。"""
from pathlib import Path

import pytest

from app.document_loader import split_document, load_pages, _table_to_md

DEMO = Path(__file__).resolve().parents[2] / "demo-data"


def test_table_to_md():
    md = _table_to_md([["名称", "单价"], ["滤芯", "85"]])
    assert md.splitlines()[0] == "| 名称 | 单价 |"
    assert "| 滤芯 | 85 |" in md


def test_pdf_radical_fix():
    """员工手册里的“部门”不能被读成部首字符“部⻔”。"""
    text = "".join(c.page_content for c in split_document(str(DEMO / "员工手册.pdf"), 1, "员工手册.pdf"))
    assert "⻔" not in text
    assert "人力资源部" in text
    assert "满10年不满20年" in text


def test_xlsx_sheets_and_header():
    chunks = split_document(str(DEMO / "2026年设备备件价格与库存表.xlsx"), 2, "备件表.xlsx")
    sheets = {c.metadata["sheet_name"] for c in chunks}
    assert sheets == {"备件价格与库存", "供应商联系表", "采购与领用规则"}
    for c in chunks:
        # 每块都带工作表名，且不超过切分长度
        assert c.page_content.startswith("工作表:")
        assert len(c.page_content) <= 500
    price = [c for c in chunks if c.metadata["sheet_name"] == "备件价格与库存"]
    # 每行都转成“列名:值”记录，单价和库存与备件在同一行
    assert all("含税单价(元):" in c.page_content for c in price)
    assert any("规格型号:LF-10;" in c.page_content and "含税单价(元):85;" in c.page_content for c in price)
    # 页码 = 工作表序号
    assert {c.metadata["page"] for c in chunks} == {1, 2, 3}


def test_pptx_slides_tables_notes():
    chunks = split_document(str(DEMO / "新员工入职培训.pptx"), 3, "培训.pptx")
    pages = {c.metadata["page"] for c in chunks}
    assert pages == set(range(1, 13))
    text = "\n".join(c.page_content for c in chunks)
    assert "| 满10年不满20年 | 10天 | 2天 | 12天 |" in text       # 表格
    assert "备注:近两年发生过两起" in text                         # 演讲者备注
    leave = [c for c in chunks if c.metadata.get("slide_title") == "带薪年假一览"]
    assert leave and leave[0].metadata["page"] == 8


def test_html(tmp_path):
    html = """<html><head><title>设备点检通知</title></head><body>
    <nav>首页 | 新闻 | 联系我们</nav>
    <article><h1>关于加强设备点检的通知</h1>
    <p>各车间：自2026年11月1日起，SK-850 设备每日开机前点检须在企业微信点检小程序中拍照上传，
    未完成点检的设备不得开机生产。设备动力部将每周抽查点检记录，抽查不合格的班组扣减当月安全绩效。</p>
    <p>本通知由设备动力部负责解释。</p></article><footer>版权所有</footer></body></html>"""
    f = tmp_path / "a.html"
    f.write_text(html, encoding="utf-8")
    pages = load_pages(str(f))
    assert "拍照上传" in pages[0][1]
    assert "联系我们" not in pages[0][1]   # 导航栏被去掉


def test_txt(tmp_path):
    f = tmp_path / "a.txt"
    f.write_text("第一段。\n\n第二段。", encoding="utf-8")
    assert len(split_document(str(f), 4, "a.txt")) == 1


def test_unsupported(tmp_path):
    f = tmp_path / "a.exe"
    f.write_bytes(b"x")
    with pytest.raises(ValueError):
        load_pages(str(f))


def test_scanned_pdf_ocr():
    """扫描件没有文字层，应自动 OCR，并在元数据中标记 ocr=True。"""
    chunks = split_document(str(DEMO / "设备点检记录表（扫描件）.pdf"), 5, "scan.pdf")
    assert chunks and all(c.metadata.get("ocr") for c in chunks)
    text = "".join(c.page_content for c in chunks)
    assert "SK850-03" in text
    assert "WX-20260918-07" in text
    assert "张伟" in text


def test_text_pdf_not_ocr():
    chunks = split_document(str(DEMO / "SK-850维护手册.pdf"), 6, "m.pdf")
    assert not any(c.metadata.get("ocr") for c in chunks)
