#!/usr/bin/env bash
# 按事件确定已发布迁移守卫的比较基线，输出 40 位 SHA；拿不到基线一律失败。
#   EVENT_NAME: push / pull_request / workflow_dispatch
#   REF_NAME:   分支名；PUSH_BEFORE: github.event.before；PR_BASE_SHA: base.sha
#   MAIN_REF:   默认 origin/main
set -euo pipefail
main_ref="${MAIN_REF:-origin/main}"
zero=0000000000000000000000000000000000000000
die() { echo "::error::迁移守卫基线：$*" >&2; exit 1; }
reachable() { git cat-file -e "$1^{commit}" 2>/dev/null; }
case "${EVENT_NAME:-}" in
  pull_request)
    base="${PR_BASE_SHA:-}"
    [[ -n "$base" ]] || die "pull_request 缺少 base.sha" ;;
  push|workflow_dispatch)
    if [[ "$EVENT_NAME" == push && "${REF_NAME:-}" == main ]]; then
      base="${PUSH_BEFORE:-}"
      [[ -n "$base" && "$base" != "$zero" ]] || die "push main 的 event.before 为空或全零"
      reachable "$base" || die "push main 的 event.before $base 不可达"
    else
      # 其他分支 / 手动触发：不看 before（新分支、force-push 时全零或不可达），
      # 一律用 merge-base(origin/main, HEAD)
      [[ -n "${REF_NAME:-}" ]] || die "缺少分支名"
      # checkout 只取当前分支的 ref，不保证有 origin/main，也可能过旧；先 fetch 再求 merge-base
      [[ "${SKIP_FETCH:-}" == 1 ]] || git fetch --quiet origin main || die "git fetch origin main 失败"
      reachable "$main_ref" || die "$main_ref 不可达（需要 fetch-depth: 0）"
      base=$(git merge-base "$main_ref" HEAD) || die "求不出 merge-base $main_ref HEAD"
    fi ;;
  *) die "不支持的事件：${EVENT_NAME:-<空>}" ;;
esac
[[ -n "$base" ]] || die "基线为空"
reachable "$base" || die "基线 $base 在仓库里不可达"
echo "$base"
