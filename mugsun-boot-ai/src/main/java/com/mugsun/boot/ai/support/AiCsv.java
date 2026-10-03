package com.mugsun.boot.ai.support;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 导出接口写 CSV，给前端的下载工具当附件用。 */
public final class AiCsv {

	private AiCsv() {
	}

	public static void write(HttpServletResponse response, String filename, String header, List<String> rows)
		throws IOException {
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType("text/csv;charset=UTF-8");
		response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
		var out = response.getOutputStream();
		out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
		out.write((header + "\n").getBytes(StandardCharsets.UTF_8));
		for (String row : rows) {
			out.write((row + "\n").getBytes(StandardCharsets.UTF_8));
		}
	}

	public static String cell(Object value) {
		String text = value == null ? "" : String.valueOf(value);
		if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
			return "\"" + text.replace("\"", "\"\"") + "\"";
		}
		return text;
	}
}
