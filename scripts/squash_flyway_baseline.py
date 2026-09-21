#!/usr/bin/env python3
"""将主库 / 埋点库多段 Flyway 收成 0.1 基线单脚本（一次性收口工具）。

正常演进请直接新增 V2__ / T2__，不要再跑本脚本覆盖基线。
用法（在 mugsun-boot 根目录）::

    python3 scripts/squash_flyway_baseline.py
    python3 scripts/pg_to_dm.py
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / "src/main/resources/db"


def ver_key(name: str, prefix: str) -> list[int]:
	m = re.match(rf"{prefix}(\d+(?:_\d+)*)__", name)
	if not m:
		return [0]
	return [int(x) for x in m.group(1).split("_")]


def squash(src_dir: Path, prefix: str, out_name: str, title: str, orig_range: str) -> None:
	files = sorted(src_dir.glob(f"{prefix}*.sql"), key=lambda p: ver_key(p.name, prefix))
	if not files:
		raise SystemExit(f"no {prefix}*.sql in {src_dir}")
	parts = [
		f"-- {title}",
		f"-- 0.1.0 基线：合并原 {orig_range} 为单脚本；全新安装只跑本文件。",
		f"-- 后续版本演进（如 0.1.1）再追加 {prefix}2__*.sql / {prefix}3__*.sql，勿再拆回历史增量。",
		"-- 生成方式：scripts/squash_flyway_baseline.py（本仓库一次性收口）。",
		"",
	]
	for p in files:
		parts.append(f"-- ========== 原 {p.name} ==========")
		parts.append(p.read_text(encoding="utf-8").rstrip() + "\n")
	out = src_dir / out_name
	tmp = src_dir / (out_name + ".tmp")
	tmp.write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")
	for p in files:
		p.unlink()
	tmp.rename(out)
	print(f"WROTE {out.relative_to(ROOT)} from {len(files)} files, {out.stat().st_size} bytes")


def main() -> None:
	squash(
		DB / "migration",
		"V",
		"V1__baseline_0_1.sql",
		"Mugsun 主库 Flyway 基线（PostgreSQL / 金仓兼容）",
		"V1–V79",
	)
	squash(
		DB / "track" / "migration",
		"T",
		"T1__baseline_0_1.sql",
		"Mugsun 埋点库 Flyway 基线（独立数据源，前缀 T）",
		"T1–T9",
	)
	dm = DB / "migration-dm"
	for p in dm.glob("V*.sql"):
		p.unlink()
	print("cleared migration-dm V*.sql — 请接着跑: python3 scripts/pg_to_dm.py")


if __name__ == "__main__":
	main()
