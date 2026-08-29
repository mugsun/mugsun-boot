#!/usr/bin/env python3
"""Convert PostgreSQL Flyway scripts to Dameng (Oracle-family) syntax."""
from __future__ import annotations

import re
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "src/main/resources/db/migration"
DST = Path(__file__).resolve().parents[1] / "src/main/resources/db/migration-dm"

HEADER = (
    "-- 由 scripts/pg_to_dm.py 从 db/migration 转换（达梦 Oracle 系）。\n"
    "-- 不引用 pg_catalog / ON CONFLICT / 部分索引 / VALUES 行构造器。\n\n"
)

# 自动转换覆盖不了的语法要手工改达梦脚本，改完在文件里留这行标记，本脚本再跑就不会覆盖。
MANUAL_MARK = "pg2dm: manual"

VALUES_RE = re.compile(
    r"FROM\s*\(\s*VALUES\s*(?P<body>.*?)\s*\)\s*AS\s+(?P<alias>\w+)\s*\((?P<cols>[^)]+)\)",
    re.IGNORECASE | re.DOTALL,
)


def convert_types(sql: str) -> str:
    sql = re.sub(r"\bbpchar\s*\(", "CHAR(", sql, flags=re.I)
    sql = re.sub(r"\bbytea\b", "BLOB", sql, flags=re.I)
    sql = re.sub(r"\bjsonb?\b", "CLOB", sql, flags=re.I)
    sql = re.sub(r"\btext\b", "CLOB", sql, flags=re.I)
    sql = re.sub(r"\btimestamptz\b", "TIMESTAMP", sql, flags=re.I)
    sql = re.sub(r"\btimestamp\b", "TIMESTAMP", sql, flags=re.I)
    sql = re.sub(r"\bdouble precision\b", "BINARY_DOUBLE", sql, flags=re.I)
    sql = re.sub(r"\bfloat8\b", "BINARY_DOUBLE", sql, flags=re.I)
    sql = re.sub(r"\bbigserial\b", "BIGINT IDENTITY", sql, flags=re.I)
    sql = re.sub(r"\bserial\b", "INT IDENTITY", sql, flags=re.I)
    sql = re.sub(r"\bint8\b", "BIGINT", sql, flags=re.I)
    sql = re.sub(r"\bint4\b", "INT", sql, flags=re.I)
    sql = re.sub(r"\bint2\b", "SMALLINT", sql, flags=re.I)
    sql = re.sub(r"\bsmallint\b", "SMALLINT", sql, flags=re.I)
    sql = re.sub(r"\bbigint\b", "BIGINT", sql, flags=re.I)
    sql = re.sub(r"\bboolean\b", "INT", sql, flags=re.I)
    sql = re.sub(r"\bvarchar\s*\(", "VARCHAR(", sql, flags=re.I)
    sql = re.sub(r"::(?:bigint|int|integer|text|varchar)", "", sql, flags=re.I)
    sql = re.sub(r"\bnow\s*\(\s*\)", "SYSDATE", sql, flags=re.I)
    sql = re.sub(r"\bTRUE\b", "1", sql)
    sql = re.sub(r"\bFALSE\b", "0", sql)
    return sql


def reorder_not_null_default(sql: str) -> str:
    return re.sub(
        r"\bNOT\s+NULL\s+DEFAULT\s+([^\s,;]+)",
        r"DEFAULT \1 NOT NULL",
        sql,
        flags=re.I,
    )


def drop_if_not_exists(sql: str) -> str:
    sql = re.sub(r"\bIF\s+NOT\s+EXISTS\b", "", sql, flags=re.I)
    sql = re.sub(r"\bIF\s+EXISTS\b", "", sql, flags=re.I)
    sql = re.sub(r"CREATE\s+INDEX\s+UNIQUE", "CREATE UNIQUE INDEX", sql, flags=re.I)
    sql = re.sub(r"ALTER\s+TABLE\s+(\S+)\s+ADD\s+COLUMN\s+", r"ALTER TABLE \1 ADD ", sql, flags=re.I)
    # 部分索引：达梦无 WHERE；含 IS NOT NULL 的 UNIQUE 会让多行 NULL 互撞，改为普通索引
    def _idx(m: re.Match) -> str:
        whole = m.group(0)
        head = m.group(1)
        if re.search(r"UNIQUE", head, re.I) and re.search(r"IS\s+NOT\s+NULL", whole, re.I):
            return re.sub(r"CREATE\s+UNIQUE\s+INDEX", "CREATE INDEX", head, flags=re.I)
        return head

    sql = re.sub(
        r"(CREATE\s+(?:UNIQUE\s+)?INDEX\s+\S+\s+ON\s+\S+\s*\([^)]+\))\s*WHERE\s+[^\n;]+",
        _idx,
        sql,
        flags=re.I,
    )
    sql = re.sub(r"\s+USING\s+btree\b", "", sql, flags=re.I)
    sql = re.sub(
        r"ALTER\s+TABLE\s+(\S+)\s+ALTER\s+COLUMN\s+(\S+)\s+TYPE\s+([^;]+)",
        r"ALTER TABLE \1 MODIFY \2 \3",
        sql,
        flags=re.I,
    )
    sql = re.sub(r"(\n\t)domain(\s+VARCHAR)", r'\1"DOMAIN"\2', sql)
    return sql


def strip_on_conflict(sql: str) -> str:
    sql = re.sub(r"\s+ON\s+CONFLICT\s+\([^)]+\)\s+DO\s+NOTHING", "", sql, flags=re.I)
    sql = re.sub(
        r"\s+ON\s+CONFLICT\s+\([^)]+\)(?:\s+WHERE[^\n]+)?\s+DO\s+UPDATE[\s\S]*?(?=;)",
        "",
        sql,
        flags=re.I,
    )
    return sql


def convert_values_ctor(sql: str) -> str:
    def repl(m: re.Match) -> str:
        body = m.group("body").strip()
        alias = m.group("alias")
        cols = [c.strip() for c in m.group("cols").split(",")]
        rows = split_value_rows(body)
        selects = []
        for i, row in enumerate(rows):
            vals = split_csv(row.strip().strip("()"))
            if i == 0:
                parts = [f"{v} AS {c}" for v, c in zip(vals, cols)]
            else:
                parts = vals
            selects.append("SELECT " + ", ".join(parts) + " FROM DUAL")
        inner = "\n\tUNION ALL\n\t".join(selects)
        return f"FROM (\n\t{inner}\n) {alias}"

    return VALUES_RE.sub(repl, sql)


def split_value_rows(body: str) -> list[str]:
    rows, buf, depth, in_str, quote = [], [], 0, False, ""
    i = 0
    while i < len(body):
        ch = body[i]
        if in_str:
            buf.append(ch)
            if ch == quote:
                if i + 1 < len(body) and body[i + 1] == quote:
                    buf.append(body[i + 1])
                    i += 2
                    continue
                in_str = False
            i += 1
            continue
        if ch in ("'", '"'):
            in_str, quote = True, ch
            buf.append(ch)
            i += 1
            continue
        if ch == "(":
            depth += 1
            buf.append(ch)
        elif ch == ")":
            depth -= 1
            buf.append(ch)
            if depth == 0:
                rows.append("".join(buf).strip())
                buf = []
        elif ch == "," and depth == 0:
            pass
        else:
            if depth > 0:
                buf.append(ch)
        i += 1
    if "".join(buf).strip():
        rows.append("".join(buf).strip())
    return [r for r in rows if r]


def split_csv(s: str) -> list[str]:
    out, buf, in_str, quote, depth = [], [], False, "", 0
    i = 0
    while i < len(s):
        ch = s[i]
        if in_str:
            buf.append(ch)
            if ch == quote:
                if i + 1 < len(s) and s[i + 1] == quote:
                    buf.append(s[i + 1])
                    i += 2
                    continue
                in_str = False
            i += 1
            continue
        if ch in ("'", '"'):
            in_str, quote = True, ch
            buf.append(ch)
        elif ch == "(":
            depth += 1
            buf.append(ch)
        elif ch == ")":
            depth -= 1
            buf.append(ch)
        elif ch == "," and depth == 0:
            out.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
        i += 1
    if buf:
        out.append("".join(buf).strip())
    return out


def convert(sql: str) -> str:
    sql = re.sub(r"DO\s+\$\$.*?END\s+\$\$\s*;",
                 "-- skipped PostgreSQL DO block (达梦用 Java/应用层回填)\n",
                 sql, flags=re.I | re.S)
    sql = convert_values_ctor(sql)
    sql = strip_on_conflict(sql)
    sql = drop_if_not_exists(sql)
    sql = convert_types(sql)
    sql = reorder_not_null_default(sql)
    # Flex DmDialect 对关键字包成大写双引号（U."TYPE"），列须同口径，禁止小写 "type"
    sql = re.sub(r"(?m)^(\s+)type(\s+)", r'\1"TYPE"\2', sql)
    sql = re.sub(r"\(processed_by,\s*type\)", '(processed_by, "TYPE")', sql)
    sql = re.sub(r"[ \t]+\n", "\n", sql)
    sql = re.sub(r"\n{3,}", "\n\n", sql)
    return HEADER + sql.strip() + "\n"


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    kept = 0
    for p in sorted(SRC.glob("V*.sql")):
        out = DST / p.name
        if out.exists() and MANUAL_MARK in out.read_text(encoding="utf-8"):
            kept += 1
            print("KEPT", out.name, "(手工维护)")
            continue
        out.write_text(convert(p.read_text()), encoding="utf-8")
        print("WROTE", out.name)
    print("DONE", len(list(DST.glob("V*.sql"))), "KEPT", kept)


if __name__ == "__main__":
    main()
