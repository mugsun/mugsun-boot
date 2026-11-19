package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.entity.AiDatasource;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiDatasourceConnectionTest {

	private static Properties props(String url, int timeoutSeconds) {
		AiDatasource row = new AiDatasource();
		row.setJdbcUrl(url);
		row.setUsername("reader");
		row.setPassword("not-a-real-one");
		return AiDatasourceBizService.connectionProperties(row, timeoutSeconds);
	}

	@Test
	void postgresGetsLimitsInSeconds() {
		Properties props = props("jdbc:postgresql://db:5432/app", 30);
		assertEquals(Set.of("user", "password", "maxResultBuffer", "connectTimeout", "socketTimeout"),
			props.stringPropertyNames());
		assertEquals("reader", props.getProperty("user"));
		assertEquals("not-a-real-one", props.getProperty("password"));
		assertEquals("32M", props.getProperty("maxResultBuffer"));
		assertEquals("10", props.getProperty("connectTimeout"));
		assertEquals("40", props.getProperty("socketTimeout"));
	}

	@Test
	void mysqlFamilyGetsLimitsInMilliseconds() {
		Properties props = props("jdbc:mysql://db:3306/app", 120);
		assertEquals(Set.of("user", "password", "connectTimeout", "socketTimeout"), props.stringPropertyNames());
		assertEquals("10000", props.getProperty("connectTimeout"));
		assertEquals("130000", props.getProperty("socketTimeout"));
	}

	@Test
	void otherDriversOnlyGetTheAccount() {
		AiDatasource row = new AiDatasource();
		row.setJdbcUrl("jdbc:dm://db:5236");
		Properties props = AiDatasourceBizService.connectionProperties(row, 30);
		assertEquals(Set.of("user", "password"), props.stringPropertyNames());
		assertEquals("", props.getProperty("user"));
		assertEquals("", props.getProperty("password"));
		assertEquals(Set.of("user", "password"),
			AiDatasourceBizService.connectionProperties(new AiDatasource(), 30).stringPropertyNames());
	}
}
