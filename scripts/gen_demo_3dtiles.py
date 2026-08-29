#!/usr/bin/env python3
"""生成 GIS 示例三维切片（3D Tiles 1.0 / b3dm）。

产物：`src/main/resources/gis/tileset/<code>/{tileset.json, *.b3dm}`，随 jar 一起发布，
由 `GisTilesetController` 按白名单 code 下发。切片内每栋楼带 `_BATCHID`，属性走 Batch Table，
Cesium 侧点选可直接 `feature.getProperty('name')` 取到楼名、层数、建成年份。

用法：python3 scripts/gen_demo_3dtiles.py
改了本脚本务必重跑并提交产物，切片是二进制、评审时以脚本为准。
"""
from __future__ import annotations

import json
import math
import struct
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/gis/tileset/demo-city"

# 示例街区落点：北京国贸一带，和默认场景中心（116.397428, 39.90923）同屏
ORIGIN_LON = 116.4551
ORIGIN_LAT = 39.9088
GROUND_HEIGHT = 0.0

GRID = 4               # 4x4 共 16 栋
SPACING = 60.0         # 栋间距（米）
FOOTPRINT = 34.0       # 楼占地边长（米）
WGS84_A = 6378137.0
WGS84_F = 1.0 / 298.257223563

USAGES = ["办公", "商业", "公寓", "酒店"]


def building_specs() -> list[dict]:
	"""按格网造楼，高度用确定性伪随机，保证每次生成结果一致。"""
	specs = []
	half = (GRID - 1) / 2.0
	for row in range(GRID):
		for col in range(GRID):
			idx = row * GRID + col
			# 越靠中心越高，叠一点确定性扰动，避免看起来像规则积木
			dist = math.hypot(row - half, col - half)
			jitter = (math.sin(idx * 12.9898) * 43758.5453) % 1.0
			height = round(28.0 + (GRID - dist) * 16.0 + jitter * 26.0, 1)
			specs.append({
				"index": idx,
				"east": (col - half) * SPACING,
				"north": (half - row) * SPACING,
				"height": height,
				"name": f"示例楼 {chr(ord('A') + row)}{col + 1}",
				"floors": max(4, int(height // 3.4)),
				"usage": USAGES[idx % len(USAGES)],
				"builtYear": 1998 + (idx * 7) % 26,
			})
	return specs


def enu_to_ecef_matrix(lon_deg: float, lat_deg: float, height: float) -> list[float]:
	"""东-北-天局部坐标到地心坐标的 4x4 变换（列主序，3D Tiles tile.transform 用）。"""
	lon = math.radians(lon_deg)
	lat = math.radians(lat_deg)
	sin_lat, cos_lat = math.sin(lat), math.cos(lat)
	sin_lon, cos_lon = math.sin(lon), math.cos(lon)

	e2 = WGS84_F * (2 - WGS84_F)
	n = WGS84_A / math.sqrt(1 - e2 * sin_lat * sin_lat)
	x = (n + height) * cos_lat * cos_lon
	y = (n + height) * cos_lat * sin_lon
	z = (n * (1 - e2) + height) * sin_lat

	east = (-sin_lon, cos_lon, 0.0)
	north = (-sin_lat * cos_lon, -sin_lat * sin_lon, cos_lat)
	up = (cos_lat * cos_lon, cos_lat * sin_lon, sin_lat)
	return [
		east[0], east[1], east[2], 0.0,
		north[0], north[1], north[2], 0.0,
		up[0], up[1], up[2], 0.0,
		x, y, z, 1.0,
	]


def box_mesh(spec: dict, batch_id: int) -> tuple[list[tuple], list[int]]:
	"""生成一栋楼的盒体。glTF 用 Y-up，故局部坐标为 (east, up, -north)。"""
	half = FOOTPRINT / 2.0
	x0, x1 = spec["east"] - half, spec["east"] + half
	z0, z1 = -spec["north"] - half, -spec["north"] + half
	y0, y1 = 0.0, spec["height"]

	faces = [
		# (四角, 法线)：逆时针朝外
		([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0.0, 0.0, 1.0)),
		([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0.0, 0.0, -1.0)),
		([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1.0, 0.0, 0.0)),
		([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1.0, 0.0, 0.0)),
		([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (0.0, 1.0, 0.0)),
		([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0.0, -1.0, 0.0)),
	]

	vertices: list[tuple] = []
	indices: list[int] = []
	for corners, normal in faces:
		base = len(vertices)
		for corner in corners:
			vertices.append((corner, normal, batch_id))
		indices += [base, base + 1, base + 2, base, base + 2, base + 3]
	return vertices, indices


def pad(data: bytes, size: int, filler: bytes) -> bytes:
	remainder = len(data) % size
	return data if remainder == 0 else data + filler * (size - remainder)


def build_glb(specs: list[dict]) -> bytes:
	"""把一批楼合成单个 glTF 2.0 二进制，逐顶点带 _BATCHID。"""
	vertices: list[tuple] = []
	indices: list[int] = []
	for batch_id, spec in enumerate(specs):
		verts, idx = box_mesh(spec, batch_id)
		offset = len(vertices)
		vertices += verts
		indices += [i + offset for i in idx]

	pos_bytes = b"".join(struct.pack("<3f", *v[0]) for v in vertices)
	nrm_bytes = b"".join(struct.pack("<3f", *v[1]) for v in vertices)
	bid_bytes = b"".join(struct.pack("<f", float(v[2])) for v in vertices)
	idx_bytes = b"".join(struct.pack("<I", i) for i in indices)

	buffer = pos_bytes + nrm_bytes + bid_bytes + idx_bytes
	pos_min = [min(v[0][i] for v in vertices) for i in range(3)]
	pos_max = [max(v[0][i] for v in vertices) for i in range(3)]

	gltf = {
		"asset": {"version": "2.0", "generator": "mugsun gen_demo_3dtiles"},
		"scene": 0,
		"scenes": [{"nodes": [0]}],
		"nodes": [{"mesh": 0}],
		"meshes": [{
			"primitives": [{
				"attributes": {"POSITION": 0, "NORMAL": 1, "_BATCHID": 2},
				"indices": 3,
				"material": 0,
				"mode": 4,
			}]
		}],
		"materials": [{
			"name": "building",
			"pbrMetallicRoughness": {
				"baseColorFactor": [0.78, 0.82, 0.88, 1.0],
				"metallicFactor": 0.05,
				"roughnessFactor": 0.85,
			},
			"doubleSided": False,
		}],
		"buffers": [{"byteLength": len(buffer)}],
		"bufferViews": [
			{"buffer": 0, "byteOffset": 0, "byteLength": len(pos_bytes), "target": 34962},
			{"buffer": 0, "byteOffset": len(pos_bytes), "byteLength": len(nrm_bytes), "target": 34962},
			{"buffer": 0, "byteOffset": len(pos_bytes) + len(nrm_bytes), "byteLength": len(bid_bytes), "target": 34962},
			{
				"buffer": 0,
				"byteOffset": len(pos_bytes) + len(nrm_bytes) + len(bid_bytes),
				"byteLength": len(idx_bytes),
				"target": 34963,
			},
		],
		"accessors": [
			{
				"bufferView": 0, "componentType": 5126, "count": len(vertices), "type": "VEC3",
				"min": pos_min, "max": pos_max,
			},
			{"bufferView": 1, "componentType": 5126, "count": len(vertices), "type": "VEC3"},
			{"bufferView": 2, "componentType": 5126, "count": len(vertices), "type": "SCALAR"},
			{"bufferView": 3, "componentType": 5125, "count": len(indices), "type": "SCALAR"},
		],
	}

	json_chunk = pad(json.dumps(gltf, separators=(",", ":")).encode("utf-8"), 4, b" ")
	bin_chunk = pad(buffer, 4, b"\x00")
	total = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
	return (
		struct.pack("<4sII", b"glTF", 2, total)
		+ struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
		+ struct.pack("<I4s", len(bin_chunk), b"BIN\x00") + bin_chunk
	)


def build_b3dm(specs: list[dict]) -> bytes:
	glb = build_glb(specs)
	feature_table = {"BATCH_LENGTH": len(specs)}
	batch_table = {
		"name": [s["name"] for s in specs],
		"height": [s["height"] for s in specs],
		"floors": [s["floors"] for s in specs],
		"usage": [s["usage"] for s in specs],
		"builtYear": [s["builtYear"] for s in specs],
	}
	ft_json = pad(json.dumps(feature_table, separators=(",", ":")).encode("utf-8"), 8, b" ")
	bt_json = pad(json.dumps(batch_table, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), 8, b" ")
	header = struct.pack(
		"<4sIIIIII",
		b"b3dm", 1,
		28 + len(ft_json) + len(bt_json) + len(glb),
		len(ft_json), 0, len(bt_json), 0,
	)
	return header + ft_json + bt_json + glb


def region(specs: list[dict], max_height: float) -> list[float]:
	"""按局部米偏移换算经纬度包围盒（region 单位是弧度）。"""
	lat_rad = math.radians(ORIGIN_LAT)
	m_per_deg_lat = 111132.92 - 559.82 * math.cos(2 * lat_rad)
	m_per_deg_lon = 111412.84 * math.cos(lat_rad)
	pad_m = FOOTPRINT / 2.0 + 4.0

	lons = [ORIGIN_LON + (s["east"] + d) / m_per_deg_lon for s in specs for d in (-pad_m, pad_m)]
	lats = [ORIGIN_LAT + (s["north"] + d) / m_per_deg_lat for s in specs for d in (-pad_m, pad_m)]
	return [
		math.radians(min(lons)), math.radians(min(lats)),
		math.radians(max(lons)), math.radians(max(lats)),
		GROUND_HEIGHT, GROUND_HEIGHT + max_height,
	]


def main() -> None:
	OUT_DIR.mkdir(parents=True, exist_ok=True)
	specs = building_specs()

	# 按象限切 4 个瓦片，形成一层子瓦片，便于验证 LOD 请求确实按需下发
	quadrants: dict[str, list[dict]] = {"nw": [], "ne": [], "sw": [], "se": []}
	for spec in specs:
		key = ("n" if spec["north"] >= 0 else "s") + ("w" if spec["east"] < 0 else "e")
		quadrants[key].append(spec)

	children = []
	for name, group in quadrants.items():
		if not group:
			continue
		(OUT_DIR / f"{name}.b3dm").write_bytes(build_b3dm(group))
		children.append({
			"boundingVolume": {"region": region(group, max(s["height"] for s in group))},
			"geometricError": 0.0,
			"refine": "REPLACE",
			"content": {"uri": f"{name}.b3dm"},
		})

	tileset = {
		"asset": {"version": "1.0", "tilesetVersion": "mugsun-demo-city-1.0"},
		"properties": {
			"height": {
				"minimum": min(s["height"] for s in specs),
				"maximum": max(s["height"] for s in specs),
			}
		},
		"geometricError": 120.0,
		"root": {
			"transform": enu_to_ecef_matrix(ORIGIN_LON, ORIGIN_LAT, GROUND_HEIGHT),
			"boundingVolume": {"region": region(specs, max(s["height"] for s in specs))},
			"geometricError": 60.0,
			"refine": "ADD",
			"children": children,
		},
	}
	(OUT_DIR / "tileset.json").write_text(
		json.dumps(tileset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
	)

	total = sum(p.stat().st_size for p in OUT_DIR.iterdir())
	print(f"生成 {len(specs)} 栋楼 / {len(children)} 个瓦片 → {OUT_DIR}（合计 {total / 1024:.1f} KB）")


if __name__ == "__main__":
	main()
