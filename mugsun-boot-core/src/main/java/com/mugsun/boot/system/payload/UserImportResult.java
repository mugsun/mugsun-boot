package com.mugsun.boot.system.payload;

import java.util.List;

/**
 * 用户导入结果：成功/失败计数 + 逐行失败明细 + 新建账号的一次性口令（仅本次响应）。
 */
public record UserImportResult(int successCount, int failCount, List<FailRow> failList, List<CreatedRow> createdList) {

	/**
	 * 失败行明细。
	 *
	 * @param rowIndex Excel 物理行号（表头占第 1 行，首条数据为第 2 行）
	 * @param username 该行用户名（trim 后）
	 * @param reason   失败原因
	 */
	public record FailRow(int rowIndex, String username, String reason) {
	}

	/**
	 * 新建成功行：一次性口令仅此响应返回，勿落日志。
	 *
	 * @param rowIndex         Excel 物理行号
	 * @param username         用户名
	 * @param initialPassword  一次性口令
	 */
	public record CreatedRow(int rowIndex, String username, String initialPassword) {
	}
}
