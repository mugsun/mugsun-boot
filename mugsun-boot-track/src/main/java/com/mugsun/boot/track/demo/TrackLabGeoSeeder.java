package com.mugsun.boot.track.demo;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import com.mugsun.boot.track.TrackGeo;
import com.mugsun.boot.track.TrackIngestEvent;
import com.mugsun.boot.track.TrackEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实验室/演示：补一批带 geo 的事件，空间行为地图可见。
 * 开关同 fronttest：{@code mugsun.lab.seed-fronttest=true}；幂等（应用已存在则跳过）。
 */
@Component
@Order(80)
@TrackDS
public class TrackLabGeoSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TrackLabGeoSeeder.class);

	private final boolean enabled;
	private final JdbcTemplate jdbc;
	private final TrackEventStore store;

	public TrackLabGeoSeeder(@Value("${mugsun.lab.seed-fronttest:false}") boolean enabled,
							 DataSource dataSource, TrackEventStore store) {
		this.enabled = enabled;
		this.jdbc = new JdbcTemplate(dataSource);
		this.store = store;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		String appKey = TrackConstants.LAB_GEO_APP_KEY;
		Long exists = jdbc.queryForObject(
			"SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0", Long.class, appKey);
		if (exists != null && exists > 0) {
			log.debug("[lab] track geo seed skip：应用 {} 已存在", appKey);
			return;
		}
		String tenant = "000000";
		jdbc.update(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " geo_enabled, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, 1, now(), now(), 0)",
			IdUtil.getSnowflakeNextId(), appKey, "空间行为演示", tenant);

		long now = System.currentTimeMillis();
		double[][] coords = {
			{116.3974, 39.9092}, {116.4040, 39.9150}, {116.3910, 39.9070},
			{121.4737, 31.2304}, {121.4800, 31.2360},
			{120.1551, 30.2741}, {120.1600, 30.2800},
			{113.2644, 23.1291}, {114.0579, 22.5431},
			{104.0665, 30.5723}, {108.9402, 34.3416}, {106.5516, 29.5630},
		};
		List<TrackIngestEvent> events = new ArrayList<>(coords.length);
		for (int i = 0; i < coords.length; i++) {
			double[] ll = TrackGeo.clamp(coords[i][0], coords[i][1]);
			if (ll == null) {
				continue;
			}
			TrackIngestEvent e = new TrackIngestEvent();
			e.setEventId(UUID.randomUUID().toString());
			e.setAppKey(appKey);
			e.setEventName(i % 3 == 0 ? TrackConstants.EVENT_PAGEVIEW : "$click");
			e.setClientTsMs(now - i * 1000L);
			e.setTsMs(now - i * 1000L);
			e.setReceivedAtMs(now - i * 1000L);
			e.setClockSkewed(0);
			e.setDistinctId("lab-geo-" + i);
			e.setSessionId(UUID.randomUUID().toString());
			e.setTenantId(tenant);
			e.setUrlPath("/lab/spatial");
			e.setRoutePath("/lab/spatial");
			e.setGeoLon(ll[0]);
			e.setGeoLat(ll[1]);
			e.setIpRegion("中国|0|演示|演示|实验室");
			e.setPropsJson("{\"geo_lon\":" + ll[0] + ",\"geo_lat\":" + ll[1] + "}");
			events.add(e);
		}
		store.insertEvents(events);
		log.info("[lab] track geo seed：应用 {} 写入 {} 条带坐标事件", appKey, events.size());
	}
}
