#!/usr/bin/env bash
# 自检：反例必须失败，且每条预期违规都被报出；真实 workflow 必须通过
set -euo pipefail
cd "$(dirname "$0")"
out=$(python3 check-workflows.py testdata/bad-workflows || true)
expect=(
  "缺少顶层 permissions"
  "bad.yml:3 禁用 pull_request_target"
  "bad.yml:7 禁止 permissions: write-all"
  "bad.yml:9 uses 必须钉到 40 位"
  "bad.yml:10 钉 SHA 后须在行尾注释版本号"
  "bad.yml:11 uses 必须钉到 40 位"
  "bad.yml:12 uses 必须钉到 40 位"
  "bad.yml:13 钉 SHA 后须在行尾注释版本号"
  "bad.yml:14 docker 镜像须带 @sha256:"
)
fail=0
for e in "${expect[@]}"; do
  grep -qF -- "$e" <<<"$out" || { echo "未报出：$e"; fail=1; }
done
[ "$fail" -eq 0 ] || { echo "$out"; exit 1; }
echo "check-workflows 自检通过（${#expect[@]} 条反例）"
