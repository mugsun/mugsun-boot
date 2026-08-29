-- 三维切片图层：kind 增加 3dtiles（倾斜摄影 / 实景模型），data_json 存 tileset 入口与渲染参数。
COMMENT ON COLUMN gis_layer.kind IS 'vector/heatmap/xyz/wms/3dtiles';
