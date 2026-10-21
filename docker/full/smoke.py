"""一键全栈自检：登录（行为验证码答案取自 Redis 挑战记录，仅供自检）→ 上报 1 条埋点 → 管理端查到该事件。
在 compose 内网的一次性容器里运行，由 scripts/compose-full-smoke.sh 调用。"""
import json, os, sys, time, uuid, urllib.request, subprocess
from gmssl import sm2

BASE = os.environ.get("BASE", "http://pc:8080")  # 经 pc 镜像的非 root Nginx（容器内 8080）/api 反代，与浏览器同路径
env = {}
for line in open("/secrets/boot/boot.env"):  # 自检容器以 root 只读挂载后端的 secrets 卷
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
# 行为验证码：键盘模式只出 SLIDER/ROTATE，平台默认 SLIDER,WORD_CLICK 下必为 SLIDER；答案从 Redis 挑战记录读（仅供自检）
cap = call("POST", "/api/auth/captcha/challenge", {"scene": "login", "clientId": "web", "tenantId": "000000",
                                                 "inputMode": "keyboard"})
assert cap.get("code") == 200, f"取验证码失败: {cap}"
cap = cap["data"]
assert cap["type"] == "SLIDER", f"键盘模式应为 SLIDER: {cap['type']}"
rec = r.get("mugsun:captcha:v2:challenge:" + cap["captchaId"])
assert rec, "Redis 里没有验证码答案"
x = int(rec.split("|")[0]); step = cap.get("keyboardStep") or 4
time.sleep(1)
end = int(time.time() * 1000)
ver = call("POST", "/api/auth/captcha/verify", {"captchaId": cap["captchaId"], "inputMode": "keyboard",
                                             "bgDisplayWidth": cap["bgWidth"], "startTime": end - 800, "endTime": end,
                                             "answer": {"x": x}, "keyPresses": round(x / step)})
assert ver.get("code") == 200, f"验证码校验失败: {ver}"
enc = sm2.CryptSM2(private_key="", public_key=pub[2:] if pub.startswith("04") else pub, mode=1).encrypt(pw.encode()).hex()
res = call("POST", "/api/auth/login", {"username": "admin", "password": enc, "tenantId": "000000", "clientId": "web",
                                     "captchaToken": ver["data"]["captchaToken"]})
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
        break
    time.sleep(2)
else:
    print(f"FAIL 120 秒内管理端没看到事件（实时流 {seen_rt}，落库 {seen_db}）", file=sys.stderr); sys.exit(1)

# 管理员改口令：之后 init show 不应再显示初始口令（由 compose-full-smoke.sh 检查）
def sm2enc(text):
    return sm2.CryptSM2(private_key="", public_key=pub[2:] if pub.startswith("04") else pub, mode=1).encrypt(text.encode()).hex()
new_pw = "Smk-" + uuid.uuid4().hex[:12] + "A9"
res = call("POST", "/api/auth/update-password", {"oldPassword": sm2enc(pw), "newPassword": sm2enc(new_pw)}, token=token)
assert res.get("code") == 200, f"改口令失败: {res}"
print("OK 管理员已改口令")
