package com.mugsun.boot.common.excel;

import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.web.excel.ExcelUtil;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 表格导入的公共校验：所有 Excel 导入入口统一经此读取，保证同一类坏文件给出同一句提示。
 * <ol>
 *   <li>没选文件 → {@link #MSG_NO_FILE}</li>
 *   <li>0 字节 → {@link #MSG_EMPTY}</li>
 *   <li>扩展名不是 xlsx/xls，或文件头魔数与扩展名对不上（如文本改名 .xlsx）→ {@link #MSG_BAD_FORMAT}</li>
 *   <li>解析失败 → {@link #MSG_BAD_FORMAT}</li>
 *   <li>解析出 0 行 → 由调用方给出业务化的「表格里没有数据」提示（{@link #requireRows}）</li>
 * </ol>
 * 全部以 {@link ServiceException}（业务 400）抛出，不会落成 500。
 */
public final class ImportFiles {

	public static final String MSG_NO_FILE = "请先选择要导入的表格文件";
	public static final String MSG_EMPTY = "文件为空。请选择填好数据的表格后再导入";
	public static final String MSG_BAD_FORMAT = "文件格式不对。请上传 Excel 表格（.xlsx 或 .xls），可以先下载模板按表头填写";

	/** xlsx（OOXML，本质是 zip）：PK\3\4 */
	private static final byte[] MAGIC_ZIP = {0x50, 0x4B, 0x03, 0x04};
	/** xls（OLE2 复合文档）：D0 CF 11 E0 A1 B1 1A E1 */
	private static final byte[] MAGIC_OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
		(byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

	private ImportFiles() {
	}

	/** 校验并读取整张表；不做 0 行判断（交给 {@link #requireRows} 给业务提示）。 */
	public static <T> List<T> readExcel(MultipartFile file, Class<T> clazz) {
		checkExcel(file);
		List<T> rows;
		try {
			rows = ExcelUtil.read(file, clazz);
		} catch (RuntimeException ex) {
			throw new ServiceException(MSG_BAD_FORMAT);
		}
		return rows == null ? List.of() : rows;
	}

	/** 解析出 0 行时以业务提示拒绝：导入 0 条不算成功。 */
	public static <T> List<T> requireRows(List<T> rows, String emptyMessage) {
		if (rows == null || rows.isEmpty()) {
			throw new ServiceException(emptyMessage);
		}
		return rows;
	}

	/** 只做文件层校验：选了没有、是否 0 字节、扩展名与文件头是否一致。 */
	public static void checkExcel(MultipartFile file) {
		if (file == null) {
			throw new ServiceException(MSG_NO_FILE);
		}
		if (file.getSize() <= 0) {
			throw new ServiceException(MSG_EMPTY);
		}
		byte[] head = readHead(file, MAGIC_OLE2.length);
		if (head.length == 0) {
			throw new ServiceException(MSG_EMPTY);
		}
		String ext = extension(file.getOriginalFilename());
		boolean ok = switch (ext) {
			case "xlsx" -> startsWith(head, MAGIC_ZIP);
			case "xls" -> startsWith(head, MAGIC_OLE2);
			// 无扩展名（部分客户端不带文件名）：按魔数认任一 Excel 格式
			case "" -> startsWith(head, MAGIC_ZIP) || startsWith(head, MAGIC_OLE2);
			default -> false;
		};
		if (!ok) {
			throw new ServiceException(MSG_BAD_FORMAT);
		}
	}

	static String extension(String name) {
		if (name == null) {
			return "";
		}
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		String base = name.substring(slash + 1);
		int dot = base.lastIndexOf('.');
		return dot < 0 ? "" : base.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
	}

	private static byte[] readHead(MultipartFile file, int n) {
		try (InputStream in = file.getInputStream()) {
			return in.readNBytes(n);
		} catch (IOException e) {
			throw new ServiceException(MSG_BAD_FORMAT);
		}
	}

	private static boolean startsWith(byte[] data, byte[] magic) {
		if (data.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (data[i] != magic[i]) {
				return false;
			}
		}
		return true;
	}
}
