"""一键全栈自检：登录（取验证码答案于 Redis，仅供自检）→ 上报 1 条埋点 → 管理端查到该事件。
在 compose 内网的一次性容器里运行，由 scripts/compose-full-smoke.sh 调用。"""
import json, os, sys, time, uuid, urllib.request, subprocess
from gmssl import sm2

BASE = os.environ.get("BASE", "http://pc")  # 经 Nginx /api 反代，与浏览器同路径
env = {}
for line in open("/secrets/mugsun.env"):
    if line.startswith("export "):
        k, v = line[7:].strip().split("=", 1)
        env[k] = v
pw = env["MUGSUN_INIT_PASSWORD"]; pub = env["MUGSUN_SM2_PUBLIC_KEY"]; rpw = env["REDIS_PASSWORD"]

def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())

import redis
r = redis.Redis(host="redis", password=rpw, db=3, decode_responses=True)
cap = call("GET", "/api/auth/captcha")["data"]
code = r.get("mugsun:captcha:" + cap["captchaUuid"])
assert code, "Redis 里没有验证码答案"
enc = sm2.CryptSM2(private_key="", public_key=pub[2:] if pub.startswith("04") else pub, mode=1).encrypt(pw.encode()).hex()
res = call("POST", "/api/auth/login", {"username": "admin", "password": enc, "tenantId": "000000",
                                     "captchaUuid": cap["captchaUuid"], "captchaCode": code})
assert res.get("code") == 200, f"登录失败: {res}"
d = res["data"]
token = d.get("accessToken") or d.get("token") or d.get("tokenValue")
assert token, f"登录响应里没有 token: {list(d)}"
print("OK 登录 admin")

eid = str(uuid.uuid4())  # event_id 上限 36 位
now = int(time.time() * 1000)
ev = {"event_id": eid, "event": "$pageview", "ts": now, "distinct_id": "smoke-user",
      "session_id": "smoke-session", "props": {"$url": "http://localhost/smoke", "$path": "/smoke"}}
res = call("POST", "/api/track/collect", {"app_key": "ak_000000000000000000000001", "schema_version": 1,
          "sdk": {"platform": "web", "version": "smoke"}, "sent_at": now, "events": [ev]})
assert res.get("code") == 200 and res["data"]["received"] >= 1, f"埋点上报失败: {res}"
print("OK 上报埋点", eid)

# 实时流（Redis Stream，带 eventId）看到这条事件 = 管理台「实时事件」可见；事件分析（落库聚合）里 $pageview 计数 ≥1 = 已落库
seen_rt = seen_db = False
for i in range(60):
    if not seen_rt:
        rt = call("GET", "/api/system/track/events/realtime?appKey=ak_000000000000000000000001", token=token)
        seen_rt = rt.get("code") == 200 and eid in json.dumps(rt)
    if not seen_db:
        page = call("GET", "/api/system/track/events/page?appKey=ak_000000000000000000000001&pageNum=1&pageSize=50", token=token)
        seen_db = any(r.get("eventName") == "$pageview" and r.get("eventCount", 0) >= 1
                      for r in (page.get("data") or {}).get("records", []))
    if seen_rt and seen_db:
        print("OK 管理端看到第一条埋点事件（实时流 + 已落库）")
        sys.exit(0)
    time.sleep(2)
print(f"FAIL 120 秒内管理端没看到事件（实时流 {seen_rt}，落库 {seen_db}）", file=sys.stderr); sys.exit(1)
