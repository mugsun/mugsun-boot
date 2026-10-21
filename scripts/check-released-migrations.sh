#!/usr/bin/env bash
# 已发布的 Flyway 迁移不可修改：逐个比对 db/RELEASED.sha256 中登记的文件哈希。
# 只允许新增 Vn__*.sql；发版时把新迁移追加进清单（scripts/check-released-migrations.sh --print 生成行）。
set -euo pipefail
cd "$(dirname "$0")/.."
MANIFEST=db/RELEASED.sha256
if [[ "${1:-}" == "--print" ]]; then
  shift; for f in "$@"; do shasum -a 256 "$f"; done; exit 0
fi
fail=0
# 清单本身只能追加：与基线比，有删改行即失败。BASE_SHA 由 CI 传入
# （scripts/ci/resolve-migration-base.sh 求出）；为空或不可达一律失败，不跳过
if [[ -z "${BASE_SHA:-}" ]]; then
  echo "::error::BASE_SHA 为空，无法比对已发布清单"; exit 1
fi
if ! git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null; then
  echo "::error::BASE_SHA ${BASE_SHA} 在仓库里不可达"; exit 1
fi
removed=$(git diff "$BASE_SHA" -- "${MANIFEST}" | grep -E '^-[^-]' || true)
if [[ -n "$removed" ]]; then echo "::error file=${MANIFEST}::已发布清单只能追加，不能删改："; echo "$removed"; fail=1; fi
while read -r sum path; do
  [[ -z "${sum}" || "${sum}" == \#* ]] && continue
  if [[ ! -f "${path}" ]]; then echo "::error file=${path}::已发布迁移被删除或改名：${path}"; fail=1; continue; fi
  actual=$(shasum -a 256 "${path}" | awk '{print $1}')
  if [[ "${actual}" != "${sum}" ]]; then
    echo "::error file=${path}::已发布迁移被修改：${path}（期望 ${sum}，实际 ${actual}）。请恢复原文件，改动写进新的 Vn__*.sql。"; fail=1
  fi
done < "${MANIFEST}"
[[ $fail -eq 0 ]] && echo "released migrations OK ($(grep -cv '^#' "${MANIFEST}") files)"
exit $fail
