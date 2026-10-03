package com.mugsun.boot.ai.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 页面日期控件提交 {@code yyyy-MM-dd HH:mm:ss}，未选时是空串。
 */
public class AiDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Override
	public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		String text = parser.getValueAsString();
		if (text == null || text.isBlank()) {
			return null;
		}
		String value = text.trim().replace('T', ' ');
		if (value.length() == 10) {
			value = value + " 00:00:00";
		}
		if (value.length() > 19) {
			value = value.substring(0, 19);
		}
		return LocalDateTime.parse(value, FORMAT);
	}
}
