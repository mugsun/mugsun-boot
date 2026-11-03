"""#102：og 目录禁止 ADD COLUMN IF NOT EXISTS；转换器须改成 DO 块；lite 替代脚本存在。"""
from __future__ import annotations

import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OG_DIR = ROOT / "mugsun-boot-core/src/main/resources/db/migration-og"
LITE = ROOT / "scripts/og/V8_oper_log_chain_guard_lite.sql"
CONVERTER = ROOT / "scripts/pg_to_opengauss.py"
BANNED = re.compile(r"(?i)ADD\s+COLUMN\s+IF\s+NOT\s+EXISTS")

def sql_without_line_comments(text: str) -> str:
    """去掉 -- 行注释后再扫，避免说明文字误伤。"""
    return "\n".join(line.split("--", 1)[0] for line in text.splitlines())



def load_converter():
    spec = importlib.util.spec_from_file_location("pg_to_opengauss", CONVERTER)
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


class OgMigrationBanTest(unittest.TestCase):
    def test_migration_og_has_no_add_column_if_not_exists(self):
        self.assertTrue(OG_DIR.is_dir(), OG_DIR)
        hits = []
        for p in sorted(OG_DIR.glob("*.sql")):
            text = sql_without_line_comments(p.read_text(encoding="utf-8"))
            if BANNED.search(text):
                hits.append(p.name)
        self.assertEqual(hits, [], f"og 脚本含禁止语法: {hits}")

    def test_lite_v8_script_exists_and_uses_do_block(self):
        self.assertTrue(LITE.is_file(), LITE)
        text = LITE.read_text(encoding="utf-8")
        self.assertIsNone(BANNED.search(sql_without_line_comments(text)))
        self.assertIn("information_schema.columns", text)
        self.assertIn("chain_prev", text)
        self.assertIn("sys_oper_log_chain", text)

    def test_converter_rewrites_add_column_if_not_exists_to_do(self):
        mod = load_converter()
        out = mod.convert(
            "ALTER TABLE sys_oper_log ADD COLUMN IF NOT EXISTS chain_prev VARCHAR(64);\n"
        )
        self.assertIsNone(BANNED.search(out))
        self.assertIn("information_schema.columns", out)
        self.assertIn("ADD COLUMN chain_prev VARCHAR(64)", out)
        self.assertIn("DO $$", out)

    def test_converter_drops_add_when_column_in_create_table(self):
        mod = load_converter()
        src = (
            "CREATE TABLE IF NOT EXISTS t (\n"
            "\tid INT PRIMARY KEY,\n"
            "\tname VARCHAR(64)\n"
            ");\n"
            "ALTER TABLE t ADD COLUMN IF NOT EXISTS name VARCHAR(64);\n"
        )
        out = mod.convert(src)
        self.assertNotIn("ADD COLUMN", out)
        self.assertIsNone(BANNED.search(out))


if __name__ == "__main__":
    unittest.main()
