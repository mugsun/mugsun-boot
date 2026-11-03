#!/usr/bin/env python3
"""openGauss-lite 5.1 装 PG 迁移脚本前的改写。

5.1 的 INSERT 没有 PostgreSQL 的 ON CONFLICT，官方等价写法是
ON DUPLICATE KEY UPDATE NOTHING（docs/5.1.0 SQLReference/insert）。
5.1 的 ALTER TABLE 没有 ADD COLUMN IF NOT EXISTS：列已经在 CREATE TABLE 里就删掉这条 ALTER；
否则改写成 information_schema DO 块（同 V9/V10 / scripts/og/V8_*_lite.sql），禁止输出里残留该语法。
5.1 的 CREATE INDEX 没有 WHERE，部分索引去掉谓词。
建库必须 DBCOMPATIBILITY 'PG'，默认库是 Oracle 兼容。
PG / 金仓仍走 db/migration，不要改原脚本。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


_VECTOR_FALLBACK = """ALTER TABLE ai_knowledge_asset ADD COLUMN content_text TEXT;
CREATE TABLE ai_kb_vector (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	knowledge_id    BIGINT NOT NULL,
	segment_id      BIGINT NOT NULL,
	asset_id        BIGINT,
	dims            INT NOT NULL DEFAULT 768,
	content_preview VARCHAR(512),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_ai_kb_vector_seg ON ai_kb_vector (segment_id);
CREATE INDEX idx_ai_kb_vector_kb ON ai_kb_vector (knowledge_id);
"""

_ADD_IF_NOT_EXISTS = re.compile(
    r"ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+IF\s+NOT\s+EXISTS\s+(\w+)\s+([^;]+);",
    re.I,
)


def rewrite_do_blocks(sql: str) -> str:
    out: list[str] = []
    cursor = 0
    start_token = "DO $$"
    end_token = "END $$;"
    while True:
        start = sql.find(start_token, cursor)
        if start < 0:
            out.append(sql[cursor:])
            break
        end = sql.find(end_token, start)
        if end < 0:
            out.append(sql[cursor:])
            break
        end += len(end_token)
        out.append(sql[cursor:start])
        block = sql[start:end]
        if "extname" in block and "postgis" in block:
            out.append("-- openGauss-lite 无 PostGIS：不建 gis_feature，空间查询走 Java 回落\n")
        elif "ai_kb_vector" in block and "vector" in block:
            out.append(_VECTOR_FALLBACK)
        else:
            out.append(block)
        cursor = end
    return "".join(out)


def rewrite_add_column_if_not_exists(sql: str) -> str:
    """lite 5.1 无 ADD COLUMN IF NOT EXISTS → DO 块查 information_schema（同 V9/V10）。"""

    def repl(m: re.Match[str]) -> str:
        table, col, rest = m.group(1), m.group(2), m.group(3).strip()
        return (
            "DO $$\n"
            "BEGIN\n"
            "\tIF NOT EXISTS (SELECT 1 FROM information_schema.columns\n"
            f"\t               WHERE table_schema = current_schema() AND table_name = '{table.lower()}'"
            f" AND column_name = '{col.lower()}') THEN\n"
            f"\t\tALTER TABLE {table} ADD COLUMN {col} {rest};\n"
            "\tEND IF;\n"
            "END $$;"
        )

    return _ADD_IF_NOT_EXISTS.sub(repl, sql)


def convert(sql: str) -> str:
    sql = re.sub(
        r"\s+ON\s+CONFLICT\s*\([^)]*\)\s+DO\s+NOTHING",
        " ON DUPLICATE KEY UPDATE NOTHING",
        sql,
        flags=re.I,
    )
    columns: dict[str, set[str]] = {}
    current: str | None = None
    kept: list[str] = []
    for line in sql.splitlines(keepends=True):
        create = re.match(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(", line, re.I)
        if create:
            current = create.group(1).lower()
            columns.setdefault(current, set())
        elif current and re.match(r"\s*\);", line):
            current = None
        elif current and not line.strip().startswith("--"):
            col = re.match(r"\s*(\w+)\s+", line)
            if col and col.group(1).upper() not in {"CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN"}:
                columns[current].add(col.group(1).lower())
        add = re.match(
            r"\s*ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN(?:\s+IF\s+NOT\s+EXISTS)?\s+(\w+)\b",
            line,
            re.I,
        )
        if add and add.group(2).lower() in columns.get(add.group(1).lower(), set()):
            # 列已在同文件 CREATE TABLE 中：整句删掉
            continue
        if add:
            columns.setdefault(add.group(1).lower(), set()).add(add.group(2).lower())
        kept.append(line)
    sql = "".join(kept)
    # 仍带 IF NOT EXISTS 的 → DO 块；禁止裸留该语法
    sql = rewrite_add_column_if_not_exists(sql)
    if re.search(r"(?i)\bADD\s+COLUMN\s+IF\s+NOT\s+EXISTS\b", sql):
        raise ValueError(
            "openGauss-lite 禁止输出 ADD COLUMN IF NOT EXISTS；"
            "请改为 information_schema DO 块（见 scripts/og/V8_oper_log_chain_guard_lite.sql）"
        )
    # lite 没装 PostGIS / pgvector。只改写块自身，不能从更早的 DO $$ 一直配到文件末尾。
    sql = rewrite_do_blocks(sql)

    def index_repl(m: re.Match[str]) -> str:
        head = m.group(1)
        where = m.group(2)
        if re.search(r"\bUNIQUE\b", head, re.I) and re.search(r"IS\s+NOT\s+NULL", where, re.I):
            head = re.sub(r"(?i)CREATE\s+UNIQUE\s+INDEX", "CREATE INDEX", head)
        return head + ";"

    sql = re.sub(
        r"(CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?\S+\s+ON\s+\S+\s*\([^)]+\))\s+WHERE\s+(.*?);",
        index_repl,
        sql,
        flags=re.I | re.S,
    )
    return sql


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: pg_to_opengauss.py src.sql dest.sql", file=sys.stderr)
        sys.exit(2)
    src, dest = Path(sys.argv[1]), Path(sys.argv[2])
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(convert(src.read_text(encoding="utf-8")), encoding="utf-8")
    print("WROTE", dest)


if __name__ == "__main__":
    main()
