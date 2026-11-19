/*
 *      Copyright (c) 2018-2028, Chill Zhuang All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the dreamlu.net developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: Chill Zhuang (smallchill@163.com)
 */
package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 非 JSON 入站解析：用示例中心下发的四段原文当基准，防止「贴进去解析不出来」回归。
 */
class GisTextIngestTest {

	private final GisTextIngest ingest = new GisTextIngest(new GisGeometryCodec());

	@Test
	void wktAcceptsMultipleGeometriesOnePerLine() {
		List<Map<String, Object>> feats = ingest.parse("""
			POINT (116.397428 39.90923)
			LINESTRING (116.352 39.9078, 116.445 39.9088)""");
		assertThat(feats).hasSize(2);
		assertThat(geomType(feats.get(0))).isEqualTo("Point");
		assertThat(geomType(feats.get(1))).isEqualTo("LineString");
	}

	@Test
	void wktStillAcceptsOneGeometryAcrossLines() {
		List<Map<String, Object>> feats = ingest.parse("""
			POLYGON ((116.372 39.898, 116.428 39.898,
			116.428 39.928, 116.372 39.928, 116.372 39.898))""");
		assertThat(feats).hasSize(1);
		assertThat(geomType(feats.get(0))).isEqualTo("Polygon");
	}

	@Test
	void csvReadsLonLatHeader() {
		List<Map<String, Object>> feats = ingest.parse("""
			lon,lat,name
			116.397428,39.90923,天安门
			116.3970,39.9180,故宫""");
		assertThat(feats).hasSize(2);
		assertThat(props(feats.get(1)).get("name")).isEqualTo("故宫");
	}

	/** BOOT-117：属性值按原文保存，< > & 和引号不改写（显示时由前端转义） */
	@Test
	void csvKeepsSpecialCharactersInValues() {
		List<Map<String, Object>> feats = ingest.parse("""
			lon,lat,name,note
			116.397428,39.90923,A&B <一号店>,库存 < 10 且销量 > 5""");
		assertThat(feats).hasSize(1);
		assertThat(props(feats.get(0)).get("name")).isEqualTo("A&B <一号店>");
		assertThat(props(feats.get(0)).get("note")).isEqualTo("库存 < 10 且销量 > 5");
	}

	@Test
	void kmlKeepsQuotesInName() {
		List<Map<String, Object>> feats = ingest.parse("""
			<kml><Document><Placemark><name>老张的"一号"店</name>
			<Point><coordinates>116.3964,39.9253</coordinates></Point></Placemark></Document></kml>""");
		assertThat(feats).hasSize(1);
		assertThat(props(feats.get(0)).get("name")).isEqualTo("老张的\"一号\"店");
	}

	/** 带 XML 声明的 KML：声明行不能把格式识别带偏 */
	@Test
	void kmlReadsPlacemarksWithXmlDeclaration() {
		List<Map<String, Object>> feats = ingest.parse("""
			<?xml version="1.0" encoding="UTF-8"?>
			<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
			<Placemark><name>景山公园</name><Point><coordinates>116.3964,39.9253</coordinates></Point></Placemark>
			<Placemark><name>北海公园</name><Point><coordinates>116.3891,39.9254</coordinates></Point></Placemark>
			</Document></kml>""");
		assertThat(feats).hasSize(2);
		assertThat(geomType(feats.get(0))).isEqualTo("Point");
	}

	@Test
	void gpxReadsTrackPointsWithXmlDeclaration() {
		List<Map<String, Object>> feats = ingest.parse("""
			<?xml version="1.0" encoding="UTF-8"?>
			<gpx version="1.1"><trk><name>巡检轨迹</name><trkseg>
			<trkpt lat="39.9078" lon="116.352"/><trkpt lat="39.9076" lon="116.371"/>
			<trkpt lat="39.9074" lon="116.3974"/><trkpt lat="39.9088" lon="116.445"/>
			</trkseg></trk></gpx>""");
		assertThat(feats).hasSize(1);
		assertThat(geomType(feats.get(0))).isEqualTo("LineString");
	}

	/** 航点 lat / lon 两种先后顺序、单双引号都认；航点和轨迹点混在一起时各归各 */
	@Test
	void gpxReadsWaypointsInEitherAttributeOrder() {
		List<Map<String, Object>> feats = ingest.parse("""
			<gpx version="1.1">
			<wpt lat="39.9087" lon="116.3975"><name>天安门</name></wpt>
			<wpt lon='116.3970' lat='39.9180'/>
			<trk><trkseg><trkpt lon="116.352" lat="39.9078"/><trkpt lat="39.9088" lon="116.445"/></trkseg></trk>
			</gpx>""");
		assertThat(feats).extracting(GisTextIngestTest::geomType).containsExactly("Point", "Point", "LineString");
		assertThat(coordinates(feats.get(1))).isEqualTo(List.of(116.3970, 39.9180));
	}

	/** BOOT-113：不闭合的标签不能让正则反复回溯（旧写法耗时随标签数三次方增长，几百个就要几秒） */
	@Test
	void unclosedTagsDoNotBacktrack() {
		String gpx = "<gpx>" + "<wpt lat='1' ".repeat(40_000);
		String kml = "<kml>" + "<coordinates ".repeat(40_000) + "<name ".repeat(40_000);
		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			assertThat(ingest.parse(gpx)).isEmpty();
			assertThat(ingest.parse(kml)).isEmpty();
		});
	}

	@Test
	void rejectsTextOverTwoMegabytes() {
		String huge = "POINT (1 1)\n".repeat(GisConstants.TEXT_MAX_CHARS / 12 + 1);
		assertThatThrownBy(() -> ingest.parse(huge))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("2MB");
	}

	@Test
	void rejectsTextWithoutGeometry() {
		assertThat(ingest.parse("hello world")).isEmpty();
		assertThat(ingest.parse("")).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private static String geomType(Map<String, Object> feat) {
		return String.valueOf(((Map<String, Object>) feat.get("geometry")).get("type"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> props(Map<String, Object> feat) {
		return (Map<String, Object>) feat.get("properties");
	}

	@SuppressWarnings("unchecked")
	private static Object coordinates(Map<String, Object> feat) {
		return ((Map<String, Object>) feat.get("geometry")).get("coordinates");
	}
}
