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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
