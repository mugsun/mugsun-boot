package com.mugsun.boot.system.payload;

import java.util.List;

/**
 * 用户导入结果：成功/失败计数 + 逐行失败明细。
 */
public record UserImportResult(int successCount, int failCount, List<FailRow> failList) {

	/**
	 * 失败行明细。
	 *
	 * @param rowIndex Excel 物理行号（表头占第 1 行，首条数据为第 2 行）
	 * @param username 该行用户名（trim 后）
	 * @param reason   失败原因
	 */
	public record FailRow(int rowIndex, String username, String reason) {
	}
}
