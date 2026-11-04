-- #76：gis_feature 加「是否跨 180°」生成列（判定同 #73）。
-- ST_XMax / ST_XMin / ST_ShiftLongitude 均为 IMMUTABLE，用 GENERATED ALWAYS AS STORED，
-- 免应用侧写入维护，也无需分批回填（加列时 PG 按表达式算存量行）。
-- 无 PostGIS / 无 gis_feature 时整段跳过，与 V1 条件建表口径一致（达梦/og 无扩展也不炸）。

DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') THEN
		RAISE NOTICE 'V16：无 PostGIS，跳过 wraps_antimeridian';
		RETURN;
	END IF;
	IF to_regclass('gis_feature') IS NULL THEN
		RAISE NOTICE 'V16：无 gis_feature 表，跳过 wraps_antimeridian';
		RETURN;
	END IF;
	IF EXISTS (
		SELECT 1 FROM information_schema.columns
		WHERE table_schema = current_schema()
		  AND table_name = 'gis_feature'
		  AND column_name = 'wraps_antimeridian'
	) THEN
		RETURN;
	END IF;

	EXECUTE $c$
		ALTER TABLE gis_feature ADD COLUMN wraps_antimeridian boolean
			GENERATED ALWAYS AS (
				(ST_XMax(geom) - ST_XMin(geom) > 180)
				AND (ST_XMax(ST_ShiftLongitude(geom)) - ST_XMin(ST_ShiftLongitude(geom)) <= 180)
			) STORED NOT NULL
	$c$;
	EXECUTE $c$
		COMMENT ON COLUMN gis_feature.wraps_antimeridian IS
			'跨 180°：平面包络宽于 180° 且 ST_ShiftLongitude 后 ≤180°（#76，与 #73 判定一致；生成列）'
	$c$;
END $$;
