package com.mugsun.boot.track;

/**
 * 只读演示模式下埋点库账号只有 INSERT/SELECT：把 {@code ON CONFLICT ... DO UPDATE SET ...} 降级为
 * {@code ON CONFLICT ... DO NOTHING}（PG 的 DO UPDATE 需要 UPDATE 权限，DO NOTHING 不需要）。
 * <p>代价：会话/回放/身份/事件定义只记首次写入，后续批次的累加、时间延展不再更新。演示环境可接受，文档已写明。
 */
public final class TrackInsertOnlySql {

	private static final String DO_UPDATE = " DO UPDATE SET";

	private TrackInsertOnlySql() {
	}

	public static String insertOnly(String upsert) {
		int idx = upsert.indexOf(DO_UPDATE);
		if (idx < 0) {
			throw new IllegalArgumentException("不是 ON CONFLICT DO UPDATE 语句");
		}
		return upsert.substring(0, idx) + " DO NOTHING";
	}

	public static String pick(String upsert, boolean insertOnly) {
		return insertOnly ? insertOnly(upsert) : upsert;
	}
}
