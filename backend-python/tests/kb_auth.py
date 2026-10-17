"""测试用：按后端要求把密码 RSA-OAEP(SHA-256) 加密后登录（与前端 passwordCrypto.js 相同的协议）。"""
import base64
import json
import time

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def encrypt_password(base, password, nonce_override=None, ts=None):
    """取公钥 + 一次性 nonce，返回 base64 密文"""
    k = requests.get(f"{base}/auth/public-key").json()
    pub = serialization.load_der_public_key(base64.b64decode(k["publicKey"]))
    msg = json.dumps({"password": password, "nonce": nonce_override or k["nonce"],
                      "ts": int(time.time() * 1000) if ts is None else ts}).encode()
    c = pub.encrypt(msg, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    return base64.b64encode(c).decode()


def login_raw(base, username, password):
    return requests.post(f"{base}/auth/login", json={"username": username, "passwordCipher": encrypt_password(base, password)})


def login(base, username, password):
    r = login_raw(base, username, password)
    r.raise_for_status()
    return {"Authorization": "Bearer " + r.json()["token"]}
