package com.mugsun.boot.track;

import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;

import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * 埋点集成测试基座：track 域测试共用的查询/轮询工具。
 * <p>track 数据源动态属性（同容器 mugsun_track 库）统一由 {@link AbstractIntegrationTest} 声明——
 * 全测试套件共享同一 Spring 上下文（上下文缓存键一致），规避 warm-flow 静态 SpringUtil 的多上下文地雷；
 * 各 track 测试类切勿再自行声明 @DynamicPropertySource（声明类不同即多建上下文）。
 */
public abstract class AbstractTrackIntegrationTest extends AbstractIntegrationTest {

	/** await 轮询上限（毫秒）与步长：异步消费断言一律轮询，不用长 sleep */
	protected static final long AWAIT_TIMEOUT_MS = 15000L;
	protected static final long AWAIT_STEP_MS = 200L;

	/** 埋点库查询（long 标量，列别名 c）；track 数据源路由 */
	protected long trackLong(String sql, Object... args) {
		Row row = trackRow(sql, args);
		return row == null ? 0L : row.getLong("c");
	}

	/** 埋点库查询（单行；无行返回 null） */
	protected Row trackRow(String sql, Object... args) {
		return DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.selectOneBySql(sql, args));
	}

	/** await 轮询（≤15s）：条件达成即返回，超时失败；轮询期间瞬时异常不判负 */
	protected void awaitUntil(String desc, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			try {
				if (condition.getAsBoolean()) {
					return;
				}
			} catch (Exception ignored) {
				// 轮询期间的瞬断（如连接波动）不判负，继续等到超时
			}
			try {
				Thread.sleep(AWAIT_STEP_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("await 被中断: " + desc);
			}
		}
		fail("await 超时（" + AWAIT_TIMEOUT_MS + "ms）: " + desc);
	}
}
