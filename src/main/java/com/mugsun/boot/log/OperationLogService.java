package com.mugsun.boot.log;

import cn.hutool.crypto.SmUtil;
import com.mugsun.boot.common.crypto.Sm2Util;
import com.mugsun.boot.log.entity.SysOperLog;
import com.mugsun.boot.log.mapper.SysOperLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志异步落库 + 防篡改（哈希链 + SM2 签名，等保三级审计完整性）。
 * <p>每条记录 {@code record_hash = SM3(规范字段 ‖ prev_hash)}、{@code prev_hash} 指向上一条 record_hash 形成链，
 * 并对 record_hash 做 SM2 签名。任一历史记录被改/删/插，其后所有记录的链哈希与签名即失配，验签可精确定位首个被篡改点。
 * <p>落库串行化（synchronized）以保证链一致；链为全局（忽略租户），验签亦忽略租户。
 */
@Service
public class OperationLogService {

	private static final String GENESIS = "0";

	private final SysOperLogMapper operLogMapper;
	private final com.mugsun.boot.system.service.ParamService paramService;

	public OperationLogService(SysOperLogMapper operLogMapper,
							   com.mugsun.boot.system.service.ParamService paramService) {
		this.operLogMapper = operLogMapper;
		this.paramService = paramService;
	}

	@Async
	public void saveAsync(SysOperLog log) {
		append(log);
	}

	/** 串行化：算链哈希 + 签名 + 落库，保证哈希链一致 */
	private synchronized void append(SysOperLog log) {
		String prevHash = lastRecordHash();
		log.setPrevHash(prevHash);
		String recordHash = SmUtil.sm3(canonical(log) + "|" + prevHash);
		log.setRecordHash(recordHash);
		log.setSign(Sm2Util.signHex(recordHash));
		try {
			// 有租户上下文（认证写请求，经 TenantTaskDecorator 透传）→ 正常落库并打租户标
			operLogMapper.insertSelective(log);
		} catch (Exception e) {
			// 无租户上下文（如未认证写请求）→ 忽略隔离补插，保证审计不丢（tenant_id 空）
			TenantContext.ignore(() -> {
				operLogMapper.insertSelective(log);
				return null;
			});
		}
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
