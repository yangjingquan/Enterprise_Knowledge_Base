"""
本地模拟百炼接口（仅测试用）：/compatible-mode/v1/embeddings 与 rerank。
向量由文本的字符 n-gram 哈希得到（确定性、相似文本相似），单批超过 10 条返回 400，与真实接口一致。
用法：python tools/mock_dashscope.py 18080
"""
import hashlib
import json
import math
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CALLS = {"embeddings": 0, "rerank": 0}


def vec(text, dim=1024):
    v = [0.0] * dim
    for n in (1, 2):
        for i in range(len(text) - n + 1):
            h = int(hashlib.md5(text[i:i + n].encode()).hexdigest(), 16)
            v[h % dim] += 1.0 if (h >> 20) & 1 else -1.0
    s = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / s for x in v]


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if not self.headers.get("Authorization", "").startswith("Bearer "):
            return self._send(401, {"error": "no key"})
        if self.path.endswith("/embeddings"):
            CALLS["embeddings"] += 1
            inp = body["input"] if isinstance(body["input"], list) else [body["input"]]
            if len(inp) > 10:
                return self._send(400, {"error": "batch size is invalid, it should not be larger than 10"})
            dim = body.get("dimensions", 1024)
            return self._send(200, {"object": "list", "model": body["model"],
                                    "data": [{"index": i, "embedding": vec(t, dim)} for i, t in enumerate(inp)]})
        if self.path.endswith("/text-rerank"):
            CALLS["rerank"] += 1
            q = body["input"]["query"]
            qs = vec(q)
            res = [{"index": i, "relevance_score": max(0.0, sum(a * b for a, b in zip(qs, vec(d))))}
                   for i, d in enumerate(body["input"]["documents"])]
            res.sort(key=lambda x: -x["relevance_score"])
            return self._send(200, {"output": {"results": res}})
        self._send(404, {})


def serve(port):
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    return srv


if __name__ == "__main__":
    serve(int(sys.argv[1]) if len(sys.argv) > 1 else 18080).serve_forever()
