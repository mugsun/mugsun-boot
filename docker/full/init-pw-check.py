"""init show 用：管理员当前口令哈希是否仍是初始口令。
读环境变量 INIT_PW（明文初始口令）与 HASH（sys_user.password，形如 {bcrypt}$2a$10$...）。
退出码：0 = 仍是初始口令；1 = 已改过；2 = 哈希格式不认识（无法判断）。"""
import os, sys
import bcrypt

pw = os.environ.get("INIT_PW", "")
h = os.environ.get("HASH", "").strip()
if h.startswith("{bcrypt}"):
    h = h[len("{bcrypt}"):]
elif h.startswith("{"):
    sys.exit(2)  # 其它 DelegatingPasswordEncoder 前缀：不认识
if not h.startswith(("$2a$", "$2b$", "$2y$")):
    sys.exit(2)
try:
    sys.exit(0 if bcrypt.checkpw(pw.encode(), h.encode()) else 1)
except ValueError:
    sys.exit(2)
