"""网页导入 SSRF 防护测试：内网/本机/元数据地址、重定向到内网、DNS 重绑定都要被拒绝，公网网页正常导入。"""
import http.server
import os
import socket
import threading

import pytest

from app import web_fetch

PAGE = ("<html><head><title>测试页面</title></head><body><article><h1>测试页面</h1>"
        + "<p>这是一段用于测试网页导入功能的正文内容，长度足够被正文提取识别。</p>" * 5
        + "</article></body></html>").encode()


@pytest.fixture()
def server(tmp_path, monkeypatch):
    monkeypatch.setattr(web_fetch, "UPLOAD_DIR", str(tmp_path))

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path.startswith("/redir-internal"):
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.2:{self.server.server_port}/page")
                self.end_headers()
                return
            if self.path.startswith("/redir-loop"):
                self.send_response(302)
                self.send_header("Location", "/redir-loop")
                self.end_headers()
                return
            if self.path.startswith("/redir-ok"):
                self.send_response(301)
                self.send_header("Location", "/page")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(PAGE)

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield srv.server_port
    srv.shutdown()


@pytest.mark.parametrize("url", [
    "http://127.0.0.1/", "http://localhost:8080/", "http://192.168.1.10/", "http://10.0.0.1/",
    "http://172.18.0.5:8000/health",            # Docker 网段
    "http://169.254.169.254/latest/meta-data/",  # 云元数据
    "http://100.100.100.200/",                   # 阿里云元数据（100.64/10）
    "http://0.0.0.0/", "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/",
    "http://[::ffff:127.0.0.1]/", "http://224.0.0.1/", "http://240.0.0.1/",
    "http://2130706433/",                        # 十进制写法的 127.0.0.1
])
def test_internal_addresses_rejected(url):
    with pytest.raises(ValueError, match="不允许|无法解析"):
        web_fetch.fetch(url)


def test_bad_scheme_and_credentials():
    for u in ("ftp://example.com/", "file:///etc/passwd", "http://user:pw@example.com/"):
        with pytest.raises(ValueError):
            web_fetch.fetch(u)


def test_ip_allowed():
    assert web_fetch._ip_allowed("8.8.8.8")
    assert web_fetch._ip_allowed("2400:3200::1")
    for ip in ("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1",
               "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1", "::"):
        assert not web_fetch._ip_allowed(ip), ip


def _allow_only_127_0_0_1(monkeypatch):
    orig = web_fetch._ip_allowed
    monkeypatch.setattr(web_fetch, "_ip_allowed", lambda ip: ip == "127.0.0.1" or orig(ip))


def test_local_server_blocked_by_default(server):
    with pytest.raises(ValueError, match="不允许"):
        web_fetch.fetch(f"http://127.0.0.1:{server}/page")


def test_redirect_hops_checked(server, monkeypatch):
    _allow_only_127_0_0_1(monkeypatch)        # 模拟"127.0.0.1 是公网站点"
    r = web_fetch.fetch(f"http://127.0.0.1:{server}/redir-ok")
    assert r["title"] == "测试页面" and r["url"].endswith("/page") and os.path.exists(r["file_path"])
    with pytest.raises(ValueError, match="不允许"):
        web_fetch.fetch(f"http://127.0.0.1:{server}/redir-internal")
    with pytest.raises(ValueError, match="重定向次数过多"):
        web_fetch.fetch(f"http://127.0.0.1:{server}/redir-loop")


def test_dns_rebinding_blocked_at_connect(server, monkeypatch):
    """校验时解析到公网，建连时解析到内网：必须在建连时拒绝。"""
    real = socket.getaddrinfo
    calls = {"n": 0}

    def fake(host, port, *a, **k):
        if host == "rebind.test":
            calls["n"] += 1
            ip = "93.184.216.34" if calls["n"] == 1 else "127.0.0.1"
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port))]
        return real(host, port, *a, **k)
    monkeypatch.setattr(socket, "getaddrinfo", fake)
    with pytest.raises(ValueError, match="不允许"):
        web_fetch.fetch(f"http://rebind.test:{server}/page")
    assert calls["n"] >= 2


def test_size_limit(server, monkeypatch):
    _allow_only_127_0_0_1(monkeypatch)
    monkeypatch.setattr(web_fetch, "MAX_BYTES", 100)
    with pytest.raises(ValueError, match="网页超过"):
        web_fetch.fetch(f"http://127.0.0.1:{server}/page")


def test_guard_only_inside_fetch():
    """防护只在 fetch 期间生效，不影响服务里其他请求（如调用内网 Python 服务）。"""
    assert not getattr(web_fetch._guard, "on", False)


@pytest.mark.skipif(os.getenv("NO_NETWORK") == "1", reason="需要外网")
def test_public_page_ok(tmp_path, monkeypatch):
    monkeypatch.setattr(web_fetch, "UPLOAD_DIR", str(tmp_path))
    r = web_fetch.fetch("https://www.python.org/about/")
    assert r["title"] and os.path.getsize(r["file_path"]) > 1000
