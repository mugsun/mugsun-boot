-- 由 scripts/pg_to_dm.py 从 db/migration 转换（达梦 Oracle 系）。
-- 不引用 pg_catalog / ON CONFLICT / 部分索引 / VALUES 行构造器。

-- 三维切片图层：kind 增加 3dtiles（倾斜摄影 / 实景模型），data_json 存 tileset 入口与渲染参数。
COMMENT ON COLUMN gis_layer.kind IS 'vector/heatmap/xyz/wms/3dtiles';
