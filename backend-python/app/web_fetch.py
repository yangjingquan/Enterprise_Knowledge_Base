"""
网页导入：下载网页 HTML，保存到共享上传目录，供后续解析入库。

流程：校验地址 → 下载（限制大小和超时）→ 提取标题 → 保存为 .html 文件 → 返回标题和保存路径。
真正的正文提取在 document_loader._load_html 里用 trafilatura 完成，和普通文件走同一条入库流程。
"""
import ipaddress
import os
import re
import socket
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
import trafilatura
import urllib3.util.connection as _u3conn

# 上传目录（与 Java 容器共享），可通过环境变量覆盖
UPLOAD_DIR = os.getenv("UPLOAD_DIR", "/data/uploads")
# 网页最大 10MB，防止误导入超大页面
MAX_BYTES = 10 * 1024 * 1024
# 连接超时 5 秒、单次读取超时 15 秒；整个下载（含重定向）总时长也不超过 15 秒
TIMEOUT = (5, 15)
TOTAL_SECONDS = 15
# 重定向逐跳手工处理并校验，最多 3 次
MAX_REDIRECTS = 3
# 部分网站会拒绝没有浏览器标识的请求
HEADERS = {
    "User-Agent": "Mozilla/5.0 (KB-Importer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5",
}


# ---------- SSRF 防护 ----------
# 只允许访问公网地址：拒绝回环、内网（含 Docker 网段 172.16/12）、链路本地（169.254，含云厂商元数据地址）、
# 运营商 NAT（100.64/10）、组播、保留地址，以及 IPv6 对应地址（::1、fc00::/7、fe80::/10、IPv4 映射地址等）。
_guard = threading.local()
# 例外网段（逗号分隔 CIDR），默认空。仅用于 DNS 返回 fake-ip 的透明代理环境（如 198.18.0.0/15），生产不要设置
_EXTRA_ALLOW = [ipaddress.ip_network(c.strip(), strict=False)
                for c in os.getenv("WEB_FETCH_ALLOW_CIDRS", "").split(",") if c.strip()]
_orig_create_connection = _u3conn.create_connection


def _ip_allowed(ip: str) -> bool:
    try:
        a = ipaddress.ip_address(ip.split("%", 1)[0])
    except ValueError:
        return False
    if any(a in n for n in _EXTRA_ALLOW):
        return True
    if isinstance(a, ipaddress.IPv6Address):
        for embedded in (a.ipv4_mapped, a.sixtofour, a.teredo[1] if a.teredo else None):
            if embedded is not None:
                return _ip_allowed(str(embedded))
        if a in ipaddress.ip_network("64:ff9b::/96"):          # NAT64
            return _ip_allowed(str(ipaddress.IPv4Address(int(a) & 0xFFFFFFFF)))
    return a.is_global and not (a.is_multicast or a.is_reserved or a.is_loopback
                                or a.is_link_local or a.is_private or a.is_unspecified)


def _resolve_public(host: str, port: int) -> list:
    """解析主机名，任一解析结果不是公网地址就拒绝（防止一个域名同时解析到公网和内网）。"""
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise ValueError("无法解析该网址的域名，请检查网址是否正确") from e
    if not infos:
        raise ValueError("无法解析该网址的域名，请检查网址是否正确")
    for info in infos:
        if not _ip_allowed(info[4][0]):
            raise ValueError("出于安全考虑，不允许导入内网、本机或保留地址的网页")
    return infos


def _guarded_create_connection(address, *args, **kwargs):
    """
    下载网页期间替换 urllib3 的建连函数：建连时再解析一次并校验，然后直接连接校验过的 IP，
    防止"校验时解析到公网、真正连接时解析到内网"的 DNS 重绑定。只在 fetch() 所在线程内生效。
    """
    if not getattr(_guard, "on", False):
        return _orig_create_connection(address, *args, **kwargs)
    host, port = address
    infos = _resolve_public(host, port)
    timeout = args[0] if args else kwargs.get("timeout", socket._GLOBAL_DEFAULT_TIMEOUT)
    err = None
    for family, socktype, proto, _c, sa in infos:
        sock = socket.socket(family, socktype, proto)
        try:
            if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
                sock.settimeout(timeout)
            sock.connect(sa)
            return sock
        except OSError as e:
            err = e
            sock.close()
    raise err or OSError("连接失败")


# urllib3 的 HTTPConnection 建连时调用 urllib3.util.connection.create_connection，替换模块属性即可生效
_u3conn.create_connection = _guarded_create_connection


def _check_url(url: str):
    u = urlparse(url)
    if u.scheme not in ("http", "https") or not u.hostname:
        raise ValueError("只支持 http:// 或 https:// 开头的网址")
    if u.username or u.password:
        raise ValueError("网址中不能包含用户名或密码")
    try:
        port = u.port or (443 if u.scheme == "https" else 80)
    except ValueError as e:
        raise ValueError("网址端口不正确") from e
    _resolve_public(u.hostname, port)
    return u


def _get(url: str, deadline: float):
    """逐跳请求：每一跳都先校验地址，再请求（不自动跟随重定向）。"""
    session = requests.Session()
    session.trust_env = False            # 不走环境变量里的代理，确保连接的就是校验过的地址
    for _hop in range(MAX_REDIRECTS + 1):
        _check_url(url)
        left = deadline - time.monotonic()
        if left <= 0:
            raise requests.exceptions.Timeout()
        resp = session.get(url, headers=HEADERS, timeout=(min(TIMEOUT[0], left), min(TIMEOUT[1], left)),
                           stream=True, allow_redirects=False)
        if resp.is_redirect:
            loc = resp.headers.get("Location", "")
            resp.close()
            if not loc:
                raise ValueError("网站返回了无效的重定向")
            url = urljoin(url, loc)
            continue
        return resp
    raise requests.exceptions.TooManyRedirects()


def fetch(url: str) -> dict:
    """
    下载网页并保存。返回 {"title": 标题, "file_path": 保存路径, "url": 最终地址}。
    出错时抛出 ValueError，消息会原样返回给前端。
    """
    # 1. 只允许 http / https，挡掉 file://、ftp:// 等协议，
    #    并校验域名解析结果只能是公网地址（SSRF 防护），每次重定向都重新校验
    url = url.strip()
    u = _check_url(url)
    deadline = time.monotonic() + TOTAL_SECONDS

    # 2. 流式下载，边下边计算大小，超过上限立即中止
    _guard.on = True
    try:
        try:
            resp = _get(url, deadline)
        except requests.exceptions.SSLError as e:
            raise ValueError("网站 HTTPS 证书校验失败，无法安全下载") from e
        except requests.exceptions.Timeout as e:
            raise ValueError(f"连接网站超时（{TOTAL_SECONDS} 秒），请稍后重试或检查网址") from e
        except requests.exceptions.TooManyRedirects as e:
            raise ValueError(f"网址重定向次数过多（最多 {MAX_REDIRECTS} 次）") from e
        except requests.RequestException as e:
            raise ValueError(f"无法访问该网址（{e.__class__.__name__}），请检查网址是否正确") from e
        data = _read(resp, deadline)
    finally:
        _guard.on = False

    # 3. 解码（见下方说明）
    html = _decode(data, resp.encoding)
    return _save(html, u, resp.url)


def _read(resp, deadline: float) -> bytes:
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
                    raise ValueError(f"网页超过 {MAX_BYTES // 1024 // 1024}MB，无法导入")
                if time.monotonic() > deadline:
                    raise ValueError(f"下载网页超时（{TOTAL_SECONDS} 秒），请稍后重试")
        except requests.RequestException as e:
            raise ValueError("下载网页内容时连接中断或超时，请稍后重试") from e
        return b"".join(chunks)
    finally:
        resp.close()


def _save(html: str, u, final_url: str) -> dict:
    # 解码说明：流式下载后不能再访问 resp.apparent_encoding（它会重新读取 resp.content，
    #    抛出 "content already consumed"），所以对已下载的字节自行判断编码：
    #    响应头声明的编码 → 网页 <meta charset> → charset_normalizer 猜测 → UTF-8

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
    return {"title": title, "file_path": str(path), "url": final_url}


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
