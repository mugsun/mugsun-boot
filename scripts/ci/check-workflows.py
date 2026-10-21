#!/usr/bin/env python3
"""校验 .github/workflows 的权限、触发方式与 Actions 钉 SHA。

规则：
1. 每个 workflow 顶层必须有 permissions，且只能是 contents: read；
   需要更多权限的 job 在 job 级单独放开，禁止 write-all。
2. 禁用 pull_request_target 触发。
3. uses: 必须钉到 40 位 commit SHA，并在行尾注释版本号（# vX.Y.Z）；
   本地 action（./）不受限，docker:// 必须带 @sha256: 摘要。

只用标准库，按行解析，不依赖 PyYAML。用法：check-workflows.py [目录]
"""
import pathlib
import re
import sys

USES_RE = re.compile(r"^\s*(?:-\s+)?uses:\s*(['\"]?)([^\s'\"#]+)\1\s*(#.*)?$")
# flow-map 写法：- {uses: x@v4} / {name: a, uses: 'x@v4'}；行尾注释在 } 之后
FLOW_USES_RE = re.compile(r"[{,]\s*uses:\s*(['\"]?)([^\s'\",}#]+)\1\s*[,}].*?(#.*)?$")
SHA_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[^@]+)?@[0-9a-f]{40}$")
VERSION_COMMENT_RE = re.compile(r"^#\s*v\d+(?:\.\d+)*\b")


def strip_comment(line: str) -> str:
    return line.split("#", 1)[0].rstrip() if "#" in line else line.rstrip()


def check_top_permissions(path, lines, errors):
    for i, raw in enumerate(lines):
        line = strip_comment(raw)
        m = re.match(r"^permissions:\s*(.*)$", line)
        if not m:
            continue
        inline = m.group(1).strip()
        if inline:
            norm = inline.replace(" ", "")
            if norm not in ("{contents:read}",):
                errors.append(f"{path}:{i + 1} 顶层 permissions 只能是 contents: read")
            return
        body = []
        for raw2 in lines[i + 1:]:
            l2 = strip_comment(raw2)
            if not l2.strip():
                continue
            if not l2.startswith((" ", "\t")):
                break
            body.append(l2.strip())
        if body != ["contents: read"]:
            errors.append(f"{path}:{i + 1} 顶层 permissions 只能是 contents: read，实际 {body}")
        return
    errors.append(f"{path}: 缺少顶层 permissions: contents: read")


def check_file(path: pathlib.Path, errors):
    lines = path.read_text(encoding="utf-8").splitlines()
    check_top_permissions(path, lines, errors)
    for i, raw in enumerate(lines, 1):
        code = strip_comment(raw)
        if re.search(r"\bpull_request_target\b", code):
            errors.append(f"{path}:{i} 禁用 pull_request_target")
        if re.search(r"^\s+permissions:\s*write-all\s*$", code):
            errors.append(f"{path}:{i} 禁止 permissions: write-all，按需逐项放开")
        m = USES_RE.match(raw) or FLOW_USES_RE.search(raw)
        if not m:
            continue
        ref, comment = m.group(2), (m.group(3) or "").strip()
        if ref.startswith("./"):
            continue
        if ref.startswith("docker://"):
            if "@sha256:" not in ref:
                errors.append(f"{path}:{i} docker 镜像须带 @sha256: 摘要：{ref}")
            continue
        if not SHA_RE.match(ref):
            errors.append(f"{path}:{i} uses 必须钉到 40 位 commit SHA：{ref}")
        elif not VERSION_COMMENT_RE.match(comment):
            errors.append(f"{path}:{i} 钉 SHA 后须在行尾注释版本号（# vX.Y.Z）：{ref}")


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows")
    files = sorted(list(root.glob("*.yml")) + list(root.glob("*.yaml")))
    if not files:
        print(f"未找到 workflow：{root}")
        return 1
    errors = []
    for f in files:
        check_file(f, errors)
    if errors:
        print("\n".join(errors))
        return 1
    print(f"check-workflows: {len(files)} 个 workflow 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
