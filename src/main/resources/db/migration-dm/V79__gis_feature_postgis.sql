-- 由 scripts/pg_to_dm.py 从 db/migration 转换（达梦 Oracle 系）。
-- 不引用 pg_catalog / ON CONFLICT / 部分索引 / VALUES 行构造器。

-- 要素行表：把整层 GeoJSON（gis_layer.data_json）拆成一要素一行，落真实 geometry 列 + GiST 索引，
-- 让「视野范围内 / 半径内 / 与某面相交 / 最近邻」四类高频查询能下沉数据库，不再全量捞回 Java 遍历。
--
-- 两点刻意设计：
-- 1) 全部包在 DO 块里。没装 PostGIS 的库（含达梦，转换器会把 DO 块整段跳过）不建表也不报错，
--    应用层按 gis_feature 是否存在决定走下沉查询还是回落 data_json 的 Java 路径。
-- 2) gis_layer.data_json 保持不动。要素行是「加速副本」而非唯一真相，回填失败不影响既有读写。

-- skipped PostgreSQL DO block (达梦用 Java/应用层回填)

-- skipped PostgreSQL DO block (达梦用 Java/应用层回填)
