#!/usr/bin/env python3
"""把每套库的 Flyway SQL 迁移合并成一个基线脚本。

什么时候用
    版本还没发布、所有环境都能删库重建的时候。发布之前改库本来就直接改基线；
    这个脚本用来收回中途加进去的增量脚本，或者在换版本时把上一版的脚本并进新基线。
    合并以后旧库的迁移历史对不上，不能原地升级，只能重建。

做什么
    把目录里的 V*.sql（埋点库是 T*.sql）按版本号顺序原样拼成一个
    V1__baseline_<版本>.sql（埋点库是 T1__baseline_<版本>.sql），每段前面标出来源文件名，
    语句一个字不改，然后删掉原来的文件。目录里已经有这一版的基线时，只把其余脚本接在它后面。
    Java 迁移不动（主库 V17、埋点库 T4），版本号也不变。

不做什么
    登记在发布清单里的脚本不合并，遇到就停下。要让一条发布线作废，
    先换一份新清单（scripts/check-released-migrations.sh 里的 MANIFEST=），再来合并。

用法（在 mugsun-boot 根目录）::

    python3 scripts/squash_flyway_baseline.py 0_2 --dry-run   # 只列出会合并哪些文件
    python3 scripts/squash_flyway_baseline.py 0_2             # 合并

达梦的基线里有手写的段（带 pg2dm: manual），scripts/pg_to_dm.py 不会覆盖它。
以后改 PostgreSQL 的基线，达梦和 openGauss 的基线要手工同步。
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE_DB = ROOT / "mugsun-boot-core/src/main/resources/db"
GUARD = ROOT / "scripts/check-released-migrations.sh"
RULE = "-- " + "=" * 68

# 目录、版本前缀、标题、只属于这套库的说明行
TARGETS = [
	(CORE_DB / "migration", "V", "Mugsun 主库 Flyway 基线（PostgreSQL / 金仓）", []),
	(
		CORE_DB / "migration-dm",
		"V",
		"Mugsun 主库 Flyway 基线（达梦）",
		["-- pg2dm: manual（含手写的段，scripts/pg_to_dm.py 不会覆盖本文件；改 PostgreSQL 基线后这里手工同步）"],
	),
	(
		CORE_DB / "migration-og",
		"V",
		"Mugsun 主库 Flyway 基线（openGauss）",
		["-- openGauss 不走 Flyway，用 gsql 执行本文件，步骤见同目录 README.md。"],
	),
	(
		ROOT / "mugsun-boot-track/src/main/resources/db/track/migration",
		"T",
		"Mugsun 埋点库 Flyway 基线（独立数据源，前缀 T）",
		[],
	),
]


def version_of(name: str, prefix: str) -> tuple[int, ...]:
	m = re.match(rf"{prefix}(\d+(?:_\d+)*)__", name)
	if not m:
		raise SystemExit(f"文件名不是 {prefix}<版本>__<说明>.sql：{name}")
	return tuple(int(x) for x in m.group(1).split("_"))


def released_paths() -> set[str]:
	"""发布清单里登记的文件。清单路径取守卫脚本里的 MANIFEST=。"""
	m = re.search(r"^MANIFEST=(\S+)", GUARD.read_text(encoding="utf-8"), re.M)
	if not m:
		raise SystemExit(f"{GUARD.relative_to(ROOT)} 里找不到 MANIFEST=")
	manifest = ROOT / m.group(1)
	if not manifest.is_file():
		raise SystemExit(f"发布清单不存在：{m.group(1)}")
	out = set()
	for line in manifest.read_text(encoding="utf-8").splitlines():
		parts = line.split(None, 1)
		if len(parts) == 2 and not line.startswith("#"):
			out.add(parts[1].strip())
	return out


def header(title: str, notes: list[str], label: str) -> str:
	ver = label.replace("_", ".")
	lines = [
		f"-- {title}",
		f"-- {ver} 基线：全新安装只跑本文件。由原来的迁移脚本按版本顺序原样拼成，每段前面标了来源文件名。",
		f"-- {ver} 发布前改库直接改本文件；发布后本文件登记进发布清单，不能再改，改库要新增脚本。",
		"-- 规则见 mugsun-boot-core/src/main/resources/db/migration/README.md；生成方式：scripts/squash_flyway_baseline.py。",
		*notes,
	]
	return "\n".join(lines) + "\n"


def plan(src_dir: Path, prefix: str, title: str, notes: list[str], label: str, released: set[str]) -> tuple[Path, str, list[Path]]:
	"""返回（基线文件、合并后的内容、要并进去的脚本）；没有要并的脚本时列表为空。只检查，不写文件。"""
	rel = src_dir.relative_to(ROOT)
	files = sorted(src_dir.glob(f"{prefix}*.sql"), key=lambda p: version_of(p.name, prefix))
	if not files:
		raise SystemExit(f"{rel} 里没有 {prefix}*.sql")
	versions = [version_of(p.name, prefix) for p in files]
	if len(set(versions)) != len(versions):
		raise SystemExit(f"{rel} 里有重复的版本号")
	out = src_dir / f"{prefix}1__baseline_{label}.sql"
	blocked = [p.name for p in files if (rel / p.name).as_posix() in released]
	if blocked:
		raise SystemExit(f"{rel}：{'、'.join(blocked)} 已登记在发布清单里，不能合并")
	if files[0] == out:
		text, rest = out.read_text(encoding="utf-8").rstrip("\n") + "\n", files[1:]
	else:
		text, rest = header(title, notes, label), files
	for p in rest:
		text += f"\n{RULE}\n-- 原 {p.name}\n{RULE}\n" + p.read_text(encoding="utf-8").rstrip("\n") + "\n"
	return out, text, rest


def main() -> None:
	ap = argparse.ArgumentParser(description="把每套库的 Flyway SQL 迁移合并成一个基线脚本")
	ap.add_argument("label", help="基线版本，写成文件名里的样子，例如 0_2")
	ap.add_argument("--dry-run", action="store_true", help="只列出会合并哪些文件，不写也不删")
	args = ap.parse_args()
	if not re.fullmatch(r"\d+(?:_\d+)*", args.label):
		raise SystemExit("版本写成 0_2 这样：数字之间用下划线")
	released = released_paths()
	# 四套先全部检查完再动手，免得并到一半停下
	plans = [plan(*target, args.label, released) for target in TARGETS]
	for out, text, rest in plans:
		rel = out.parent.relative_to(ROOT)
		if not rest:
			print(f"{rel}：只有 {out.name}，不用动")
			continue
		print(f"{rel}：{len(rest)} 个脚本并进 {out.name}")
		for p in rest:
			print(f"    {p.name}")
		if args.dry_run:
			continue
		tmp = out.with_name(out.name + ".tmp")
		tmp.write_text(text, encoding="utf-8", newline="\n")
		for p in rest:
			p.unlink()
		tmp.replace(out)
	if not args.dry_run and any(rest for _, _, rest in plans):
		print("接下来：更新各目录 README 的脚本清单；用旧脚本建的库要重建。")


if __name__ == "__main__":
	main()
