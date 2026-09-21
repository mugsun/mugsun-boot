#!/usr/bin/env bash
# 兼容入口：转调 module-assemble.mjs --list / --dry-run
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec node "$ROOT/scripts/module-assemble.mjs" "${@:-"--list"}"
