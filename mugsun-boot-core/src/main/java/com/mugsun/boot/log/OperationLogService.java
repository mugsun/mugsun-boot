package com.mugsun.boot.log;

import cn.hutool.crypto.SmUtil;
import com.mugsun.boot.common.crypto.Sm2Util;
import com.mugsun.boot.log.entity.SysOperLog;
import com.mugsun.boot.log.mapper.SysOperLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantException;
import com.mugsun.boot.tenant.TenantTaskDecorator;
import com.mybatisflex.core.row.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作日志异步落库 + 防篡改（哈希链 + SM2 签名，等保三级审计完整性）。
 * <p>每条记录 {@code record_hash = SM3(规范字段 ‖ prev_hash)}、{@code prev_hash} 指向上一条 record_hash 形成链，
 * 并对 record_hash 做 SM2 签名。任一历史记录被改/删/插，其后所有记录的链哈希与签名即失配，验签可精确定位首个被篡改点。
 * <p>落库串行化（ReentrantLock）以保证链一致；链为全局（忽略租户），验签亦忽略租户。
 * <p>不用 synchronized：@Async 跑在虚拟线程上，JDK 21 下在 synchronized 上等待会钉住载体线程，
 * 持锁者等数据库连接、持连接的虚拟线程又拿不到载体线程，会互相卡死（连接池耗尽）。
 * <p>多节点：本地锁只管单实例；落库在事务内先 {@code SELECT ... FOR UPDATE} 锁住链头行 {@code sys_oper_log_chain(id=1)}，
 * 跨实例串行化；{@code chain_prev} 唯一索引兜底，万一分叉直接撞唯一约束并重试一次。
 * <p>未登录请求的审计（operator 为空）限制同时排队数（{@code mugsun.audit.anonymous-max-pending}），超出即丢弃并告警计数，
 * 防匿名洪水把审计链串行点和连接池拖垮；已登录请求不受限。
 */
@Service
public class OperationLogService {

	private static final String GENESIS = "0";

	private final java.util.concurrent.locks.ReentrantLock appendLock = new java.util.concurrent.locks.ReentrantLock();

	private static final Logger LOG = LoggerFactory.getLogger(OperationLogService.class);
	private static final String LOCK_CHAIN_HEAD = "SELECT id FROM sys_oper_log_chain WHERE id = 1 FOR UPDATE";

	private final SysOperLogMapper operLogMapper;
	private final com.mugsun.boot.system.service.ParamService paramService;
	private final TransactionTemplate tx;
	private final Semaphore anonymousPermits;
	private final AtomicLong anonymousDropped = new AtomicLong();
	private final SimpleAsyncTaskExecutor executor;

	public OperationLogService(SysOperLogMapper operLogMapper,
							   com.mugsun.boot.system.service.ParamService paramService,
							   PlatformTransactionManager txManager,
							   @Value("${mugsun.audit.anonymous-max-pending:64}") int anonymousMaxPending) {
		this.operLogMapper = operLogMapper;
		this.paramService = paramService;
		this.tx = new TransactionTemplate(txManager);
		this.anonymousPermits = new Semaphore(Math.max(1, anonymousMaxPending));
		this.executor = new SimpleAsyncTaskExecutor("mugsun-oper-log-");
		this.executor.setVirtualThreads(true);
		this.executor.setTaskDecorator(new TenantTaskDecorator());
	}

	/** 异步落库（虚拟线程 + 租户/MDC 透传）；未登录请求受排队上限保护，超出丢弃并计数 */
	public void saveAsync(SysOperLog log) {
		boolean anonymous = log.getOperator() == null || log.getOperator().isBlank();
		if (anonymous && !anonymousPermits.tryAcquire()) {
			long n = anonymousDropped.incrementAndGet();
			if (n == 1 || n % 100 == 0) {
				LOG.warn("未登录请求审计排队已满，已丢弃 {} 条（uri={}）", n, log.getRequestUri());
			}
			return;
		}
		executor.execute(() -> {
			try {
				append(log);
			} catch (RuntimeException e) {
				LOG.warn("操作日志落库失败 uri={}: {}", log.getRequestUri(), e.getMessage());
			} finally {
				if (anonymous) {
					anonymousPermits.release();
				}
			}
		});
	}

	/** 运维/测试：累计丢弃的未登录审计条数 */
	public long anonymousDroppedCount() {
		return anonymousDropped.get();
	}

	/** 串行化：算链哈希 + 签名 + 落库，保证哈希链一致 */
	private void append(SysOperLog log) {
		appendLock.lock();
		try {
			try {
				appendInTx(log);
			} catch (DuplicateKeyException e) {
				// 另一节点抢先接上了同一链头（理论上被行锁挡住，此为兜底）：重读链头重试一次
				appendInTx(log);
			}
		} finally {
			appendLock.unlock();
		}
	}

	/** 事务内：锁链头行（跨实例串行）→ 读最后哈希 → 算哈希签名 → 落库 */
	private void appendInTx(SysOperLog log) {
		tx.executeWithoutResult(status -> {
			TenantContext.ignore(() -> Db.selectObjectList(LOCK_CHAIN_HEAD));
			String prevHash = lastRecordHash();
			log.setPrevHash(prevHash);
			log.setChainPrev(prevHash);
			String recordHash = SmUtil.sm3(canonical(log) + "|" + prevHash);
			log.setRecordHash(recordHash);
			log.setSign(Sm2Util.signHex(recordHash));
			log.setId(null);
			try {
				// 有租户上下文（认证写请求，经 TenantTaskDecorator 透传）→ 正常落库并打租户标
				operLogMapper.insertSelective(log);
			} catch (RuntimeException e) {
				if (!causedByTenant(e)) {
					throw e;
				}
				// 无租户上下文（租户插件在发 SQL 前就拒绝，事务未被污染）（如未认证写请求）→ 忽略隔离补插，保证审计不丢（tenant_id 空）
				TenantContext.ignore(() -> {
					operLogMapper.insertSelective(log);
					return null;
				});
			}
		});
	}

	private static boolean causedByTenant(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof TenantException) {
				return true;
			}
		}
		return false;
	}

	/** 全局最后一条记录的 record_hash（忽略租户），无则创世 0 */
	private String lastRecordHash() {
		SysOperLog last = TenantContext.ignore(() -> operLogMapper.selectOneByQuery(
			QueryWrapper.create().orderBy("id", false).limit(1)));
		return last != null && last.getRecordHash() != null ? last.getRecordHash() : GENESIS;
	}

	/**
	 * 验签闭环：按 id 升序重算哈希链 + 验证 SM2 签名，返回校验结论与首个被篡改记录 id。
	 * @param limit &gt;0 时只校最近 limit 条（有界内存，窗口首条接受其边界 prev_hash）；否则全量从创世校起。
	 */
	public Map<String, Object> verify(Integer limit) {
		boolean windowed = limit != null && limit > 0;
		List<SysOperLog> logs;
		if (windowed) {
			List<SysOperLog> recent = TenantContext.ignore(() -> operLogMapper.selectListByQuery(
				QueryWrapper.create().orderBy("id", false).limit(limit)));
			java.util.Collections.reverse(recent);
			logs = recent;
		} else {
			logs = TenantContext.ignore(() -> operLogMapper.selectListByQuery(
				QueryWrapper.create().orderBy("id", true)));
		}
		String expectedPrev = GENESIS;
		// 截断锚点：保留期清理后全量验签自锚点续起（锚点前记录已被合法清除，不再误报断链）
		if (!windowed) {
			String anchor = paramService.getValue(
				com.mugsun.boot.common.constant.MonitorConstants.PARAM_CHAIN_ANCHOR);
			if (anchor != null && anchor.contains(":")) {
				String hash = anchor.substring(anchor.indexOf(':') + 1);
				if (!hash.isBlank()) {
					expectedPrev = hash;
				}
			}
		}
		boolean firstHashed = true;
		int verified = 0;
		for (SysOperLog log : logs) {
			// 未加固的历史记录（V52 前）不入链，跳过
			if (log.getRecordHash() == null) {
				continue;
			}
			// 窗口模式首条：接受其边界 prev_hash（无法回溯窗口外前驱）
			if (firstHashed && windowed) {
				expectedPrev = log.getPrevHash();
			}
			firstHashed = false;
			if (!expectedPrev.equals(log.getPrevHash())) {
				return tampered(log.getId(), "哈希链断裂（记录被删除或插入）", verified);
			}
			String recomputed = SmUtil.sm3(canonical(log) + "|" + log.getPrevHash());
			if (!recomputed.equals(log.getRecordHash())) {
				return tampered(log.getId(), "记录内容被篡改", verified);
			}
			if (!Sm2Util.verifyHex(log.getRecordHash(), log.getSign())) {
				return tampered(log.getId(), "签名无效（记录被伪造）", verified);
			}
			expectedPrev = log.getRecordHash();
			verified++;
		}
		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put("valid", true);
		ok.put("verified", verified);
		ok.put("message", "审计链完整，共验签 " + verified + " 条记录");
		return ok;
	}

	private Map<String, Object> tampered(Object id, String reason, int verified) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("valid", false);
		r.put("tamperedId", id);
		r.put("reason", reason);
		r.put("verifiedBefore", verified);
		return r;
	}

	/** 参与哈希的规范字段（须与验签重算完全一致；params 已在切面脱敏截断后落库）。
	 *  含 operator/来源IP/请求/方法/参数/状态/错误（均落库前显式赋值、存取一致）；
	 *  不含 tenant_id / create_time —— 二者由 Flex 插件/DB now() 落库时填，重算会失配。 */
	private String canonical(SysOperLog log) {
		return String.join("|",
			nz(log.getOperator()), nz(log.getIp()), nz(log.getRequestMethod()), nz(log.getRequestUri()),
			nz(log.getMethod()), nz(log.getParams()), String.valueOf(log.getStatus()), nz(log.getErrorMsg()));
	}

	private String nz(String s) {
		return s == null ? "" : s;
	}
}
