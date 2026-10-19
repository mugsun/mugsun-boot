package com.mugsun.boot.log;

import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.demo.LogDataSourceRouter;
import com.mugsun.boot.log.entity.SysOperLog;
import com.mugsun.boot.log.mapper.SysOperLogMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 安全官 N1/N2：哈希链跨实例串行 + chain_prev 唯一兜底；未登录审计排队上限 */
class OperLogChainGuardTest extends AbstractIntegrationTest {

	@Autowired
	OperationLogService service;
	@Autowired
	SysOperLogMapper mapper;
	@Autowired
	ParamService paramService;
	@Autowired
	PlatformTransactionManager txManager;
	@Autowired
	LogDataSourceRouter logRouter;

	private long count() {
		return TenantContext.ignore(() -> Db.selectCount("SELECT count(*) FROM sys_oper_log"));
	}

	private static SysOperLog entry(String operator, String uri) {
		SysOperLog l = new SysOperLog();
		l.setTitle("chain-guard");
		l.setRequestMethod("POST");
		l.setRequestUri(uri);
		l.setOperator(operator);
		l.setStatus(1);
		return l;
	}

	private void awaitCount(long expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (count() < expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(count()).isEqualTo(expected);
	}

	@Test
	@Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void twoInstancesConcurrentAppendsKeepSingleChain() throws Exception {
		// 两个独立实例（各自的本地锁）模拟两个节点并发写，只能靠数据库链头锁串行
		OperationLogService other = new OperationLogService(mapper, paramService, txManager, logRouter, 64);
		long before = count();
		CountDownLatch go = new CountDownLatch(1);
		for (int i = 0; i < 20; i++) {
			OperationLogService target = i % 2 == 0 ? service : other;
			int n = i;
			Thread.ofVirtual().start(() -> {
				try {
					go.await();
				} catch (InterruptedException ignored) {
				}
				TenantContext.execute(PLATFORM_TENANT, () -> target.saveAsync(entry("1", "/it/chain/" + n)));
			});
		}
		go.countDown();
		awaitCount(before + 20);
		Map<String, Object> r = service.verify(25);
		assertThat(r.get("valid")).as(String.valueOf(r)).isEqualTo(true);
		long dupChainPrev = TenantContext.ignore(() -> Db.selectCount(
			"SELECT count(*) FROM (SELECT prev_hash FROM sys_oper_log WHERE chain_prev IS NOT NULL GROUP BY prev_hash HAVING count(*) > 1) t"));
		assertThat(dupChainPrev).isZero();
	}

	@Test
	void duplicateChainPrevIsRejectedByUniqueIndex() {
		String head = "dup-" + System.nanoTime();
		Db.updateBySql("INSERT INTO sys_oper_log (id, title, chain_prev, is_deleted) VALUES (?, 'dup', ?, 0)", System.nanoTime(), head);
		assertThatThrownBy(() -> Db.updateBySql(
			"INSERT INTO sys_oper_log (id, title, chain_prev, is_deleted) VALUES (?, 'dup', ?, 0)", System.nanoTime() + 1, head))
			.hasMessageContaining("uk_oper_log_chain_prev");
		Db.updateBySql("DELETE FROM sys_oper_log WHERE chain_prev = ?", head);
	}

	@Test
	@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void anonymousAuditIsBoundedWhileAuthenticatedIsNot() throws Exception {
		OperationLogService bounded = new OperationLogService(mapper, paramService, txManager, logRouter, 1);
		long before = count();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// 另一事务占住链头锁，让排队的审计任务卡住
		Thread holder = Thread.ofVirtual().start(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
			TenantContext.ignore(() -> Db.selectObjectList("SELECT id FROM sys_oper_log_chain WHERE id = 1 FOR UPDATE"));
			locked.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			}
		}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
		for (int i = 0; i < 5; i++) {
			bounded.saveAsync(entry(null, "/it/anon/" + i));
		}
		for (int i = 0; i < 3; i++) {
			TenantContext.execute(PLATFORM_TENANT, () -> bounded.saveAsync(entry("1", "/it/auth")));
		}
		assertThat(bounded.anonymousDroppedCount()).isEqualTo(4);
		release.countDown();
		holder.join();
		awaitCount(before + 1 + 3);
	}
}
