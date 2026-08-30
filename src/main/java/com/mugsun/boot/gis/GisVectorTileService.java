package com.mugsun.boot.gis;

import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * 矢量瓦片出图：PostGIS 的 {@code ST_AsMVT} 直接在库里裁瓦片，前端按 z/x/y 取，
 * 万级要素图层不必再把整层 GeoJSON 拉到浏览器。
 *
 * <p>属性以单个 {@code props} 字符串带出（GeoJSON properties 原文），前端自行解析。
 * 这样做是因为 MVT 的属性表要求列固定，而各图层的属性键完全由入站数据决定，没有共同 schema。
 *
 * <p>没有 PostGIS 时本能力直接不可用（返回 501 语义的业务异常），不做 Java 侧模拟——
 * 裁瓦片没有下沉就没有意义，前端应回落到整层 GeoJSON 渲染。
 */
@Service
public class GisVectorTileService {

	private static final String SQL = """
		WITH bounds AS (
			SELECT ST_TileEnvelope(?, ?, ?) AS merc
		), src AS (
			SELECT ST_AsMVTGeom(ST_Transform(f.geom, 3857), bounds.merc, ?, ?, true) AS geom,
			       f.id,
			       f.props_json AS props
			FROM gis_feature f, bounds
			WHERE f.layer_id = ? AND f.is_deleted = 0
			  AND f.geom && ST_Transform(bounds.merc, 4326)
		)
		SELECT ST_AsMVT(src, ?, ?, 'geom') FROM src WHERE geom IS NOT NULL
		""";

	private final GisSpatialSupport support;
	private final GisFeatureStore featureStore;

	public GisVectorTileService(GisSpatialSupport support, GisFeatureStore featureStore) {
		this.support = support;
		this.featureStore = featureStore;
	}

	public boolean available() {
		return support.available();
	}

	/** 返回 protobuf 字节；该瓦片没有要素时返回空数组（前端按空瓦片处理，不要当错误） */
	public byte[] tile(GisLayer layer, int z, int x, int y) {
		if (!support.available()) {
			throw new ServiceException(GisConstants.MSG_MVT_UNAVAILABLE);
		}
		if (!GisFeatureStore.indexable(layer.getKind())) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_KIND);
		}
		if (z < GisConstants.TILE_MIN_Z || z > GisConstants.TILE_MAX_Z) {
			throw new ServiceException(GisConstants.MSG_MVT_ZXY);
		}
		int max = 1 << z;
		if (x < 0 || y < 0 || x >= max || y >= max) {
			throw new ServiceException(GisConstants.MSG_MVT_ZXY);
		}
		ensureRows(layer);
		byte[] pbf = support.jdbc().queryForObject(SQL, byte[].class,
			z, x, y,
			GisConstants.MVT_EXTENT, GisConstants.MVT_BUFFER,
			layer.getId(),
			GisConstants.MVT_LAYER_NAME, GisConstants.MVT_EXTENT);
		return pbf == null ? new byte[0] : pbf;
	}

	/** 存量图层第一次被瓦片请求命中时按需回填要素行 */
	private void ensureRows(GisLayer layer) {
		if (featureStore.count(layer.getId()) == 0
			&& layer.getFeatureCount() != null && layer.getFeatureCount() > 0) {
			featureStore.backfillIfEmpty(
				layer.getId(), layer.getTenantId(), layer.getKind(), layer.getDataJson());
		}
	}
}
