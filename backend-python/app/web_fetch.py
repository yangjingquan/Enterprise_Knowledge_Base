"""
网页导入：下载网页 HTML，保存到共享上传目录，供后续解析入库。

流程：校验地址 → 下载（限制大小和超时）→ 提取标题 → 保存为 .html 文件 → 返回标题和保存路径。
真正的正文提取在 document_loader._load_html 里用 trafilatura 完成，和普通文件走同一条入库流程。
"""
import os
import re
import uuid
from pathlib import Path
from urllib.parse import urlparse

import requests
import trafilatura

# 上传目录（与 Java 容器共享），可通过环境变量覆盖
UPLOAD_DIR = os.getenv("UPLOAD_DIR", "/data/uploads")
# 网页最大 5MB，防止误导入超大页面
MAX_BYTES = 5 * 1024 * 1024
# 连接超时 5 秒、读取超时 20 秒
TIMEOUT = (5, 20)
# 部分网站会拒绝没有浏览器标识的请求
HEADERS = {
    "User-Agent": "Mozilla/5.0 (KB-Importer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5",
}


def fetch(url: str) -> dict:
    """
    下载网页并保存。返回 {"title": 标题, "file_path": 保存路径, "url": 最终地址}。
    出错时抛出 ValueError，消息会原样返回给前端。
    """
    # 1. 只允许 http / https，挡掉 file://、ftp:// 等协议
    u = urlparse(url.strip())
    if u.scheme not in ("http", "https") or not u.netloc:
        raise ValueError("只支持 http:// 或 https:// 开头的网址")

    # 2. 流式下载，边下边计算大小，超过上限立即中止
    try:
        resp = requests.get(url, headers=HEADERS, timeout=TIMEOUT, stream=True, allow_redirects=True)
    except requests.exceptions.SSLError as e:
        raise ValueError("网站 HTTPS 证书校验失败，无法安全下载") from e
    except requests.exceptions.Timeout as e:
        raise ValueError("连接网站超时（20 秒），请稍后重试或检查网址") from e
    except requests.exceptions.TooManyRedirects as e:
        raise ValueError("网址重定向次数过多") from e
    except requests.RequestException as e:
        raise ValueError(f"无法访问该网址（{e.__class__.__name__}），请检查网址是否正确") from e
    try:
        if resp.status_code >= 400:
            raise ValueError(f"网站返回错误状态码 {resp.status_code}（可能需要登录或禁止抓取）")
        ctype = resp.headers.get("Content-Type", "")
        if "html" not in ctype and "xml" not in ctype and ctype:
            raise ValueError(f"该地址不是网页（类型：{ctype}），文件请直接上传")
        chunks, size = [], 0
        try:
            for part in resp.iter_content(64 * 1024):
                chunks.append(part)
                size += len(part)
                if size > MAX_BYTES:
                    raise ValueError("网页超过 5MB，无法导入")
        except requests.RequestException as e:
            raise ValueError("下载网页内容时连接中断或超时，请稍后重试") from e
        data = b"".join(chunks)
    finally:
        resp.close()

    # 3. 解码：流式下载后不能再访问 resp.apparent_encoding（它会重新读取 resp.content，
    #    抛出 "content already consumed"），所以对已下载的字节自行判断编码：
    #    响应头声明的编码 → 网页 <meta charset> → charset_normalizer 猜测 → UTF-8
    html = _decode(data, resp.encoding)

    # 4. 先试提取一次正文，提不出来说明页面是空壳（例如纯前端渲染），提前报错
    text = trafilatura.extract(html, include_tables=True, favor_recall=True)
    if not text or len(text.strip()) < 20:
        raise ValueError("未能从网页中提取到正文（可能需要登录或由脚本动态生成）")

    # 5. 取标题：优先 trafilatura 识别的标题，其次 <title> 标签，最后用域名
    meta = trafilatura.extract_metadata(html)
    title = (meta.title if meta and meta.title else "") or _html_title(html) or u.netloc
    title = re.sub(r"\s+", " ", title).strip()[:150]

    # 6. 统一以 UTF-8 保存，文件名用 UUID 避免冲突
    Path(UPLOAD_DIR).mkdir(parents=True, exist_ok=True)
    path = Path(UPLOAD_DIR) / f"{uuid.uuid4()}.html"
    path.write_text(html, encoding="utf-8")
    return {"title": title, "file_path": str(path), "url": resp.url}


def _decode(data: bytes, header_enc: str | None) -> str:
    """把网页字节解码成字符串（中文网站常见 GBK/GB2312）。"""
    candidates = []
    if header_enc and header_enc.lower() != "iso-8859-1":   # requests 对未声明编码的 text/* 默认 ISO-8859-1，不可信
        candidates.append(header_enc)
    m = re.search(rb"<meta[^>]+charset=[\"']?\s*([A-Za-z0-9_\-]+)", data[:4096], re.I)
    if m:
        candidates.append(m.group(1).decode("ascii", "ignore"))
    try:
        from charset_normalizer import from_bytes
        best = from_bytes(data[:200_000]).best()
        if best:
            candidates.append(best.encoding)
    except Exception:
        pass
    candidates.append("utf-8")
    for enc in candidates:
        enc = "gb18030" if enc.lower() in ("gb2312", "gbk") else enc   # gb18030 是 GBK 的超集，避免生僻字乱码
        try:
            return data.decode(enc)
        except (LookupError, UnicodeDecodeError):
            continue
    return data.decode("utf-8", errors="ignore")


def _html_title(html: str) -> str:
    """从 <title> 标签里取标题。"""
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.I | re.S)
    return m.group(1).strip() if m else ""
