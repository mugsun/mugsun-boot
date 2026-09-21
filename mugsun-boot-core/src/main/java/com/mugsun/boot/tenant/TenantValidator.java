package com.mugsun.boot.tenant;

import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.mapper.SysTenantMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.entity.SysTenantPackage;
import com.mugsun.boot.tenant.mapper.SysTenantPackageMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 租户生命周期校验器：状态/过期/配额/套餐的统一落点，供登录层与请求层拦截器复用。
 *
 * <p>缓存刻意采用「租户上下文无关」的本地 TTL 缓存（60s）而非 JetCache：租户与套餐是<b>平台级全局</b>数据
 * （sys_tenant 无 tenant_id），而全局 keyConvertor 会给缓存键叠加「当前租户」前缀——那会导致同一租户信息按
 * 请求方租户重复缓存，且超管在 000000 上下文的清缓存命中不到租户方上下文写入的键。故此处用不受租户前缀影响的
 * 显式缓存，写侧变更即时 evict 生效、TTL 兜底。
 *
 * <p>平台租户 {@code 000000} 永远有效、不限额、不受套餐限制（超管所属，短路豁免）。
 */
@Service
public class TenantValidator {

	/** 缓存 TTL：60 秒兜底 */
	private static final long TTL_MILLIS = 60_000L;

	private final SysTenantMapper tenantMapper;
	private final SysTenantPackageMapper packageMapper;
	private final SysUserMapper userMapper;
	private final org.springframework.data.redis.core.StringRedisTemplate redis;

	private final Map<String, Cached<SysTenant>> tenantCache = new ConcurrentHashMap<>();
	private final Map<Long, Cached<Set<String>>> packageCache = new ConcurrentHashMap<>();

	public TenantValidator(SysTenantMapper tenantMapper, SysTenantPackageMapper packageMapper, SysUserMapper userMapper,
						   org.springframework.data.redis.core.StringRedisTemplate redis) {
		this.tenantMapper = tenantMapper;
		this.packageMapper = packageMapper;
		this.userMapper = userMapper;
		this.redis = redis;
	}

	/** 按编号加载租户（sys_tenant 无 tenant_id，忽略隔离；登录前无会话亦可查）。本地缓存 60s，不缓存 null。 */
	public SysTenant loadTenant(String code) {
		Cached<SysTenant> hit = tenantCache.get(code);
		if (hit != null && hit.fresh()) {
			return hit.value;
		}
		SysTenant tenant = TenantContext.ignore(() ->
			tenantMapper.selectOneByQuery(QueryWrapper.create().eq("tenant_code", code)));
		if (tenant != null) {
			tenantCache.put(code, new Cached<>(tenant));
		}
		return tenant;
	}

	public void evict(String code) {
		tenantCache.remove(code);
	}

	/** 加载套餐允许的前端路由 name 集合。本地缓存 60s。 */
	public Set<String> loadPackageKeys(Long packageId) {
		Cached<Set<String>> hit = packageCache.get(packageId);
		if (hit != null && hit.fresh()) {
			return hit.value;
		}
		SysTenantPackage pkg = packageMapper.selectOneById(packageId);
		Set<String> keys;
		if (pkg == null || pkg.getMenuKeys() == null || pkg.getMenuKeys().isBlank()) {
			keys = Set.of();
		} else {
			keys = Arrays.stream(pkg.getMenuKeys().split(","))
				.map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
		}
		packageCache.put(packageId, new Cached<>(keys));
		return keys;
	}

	public void evictPackage(Long packageId) {
		packageCache.remove(packageId);
	}

	/** 校验租户可用性，返回不可用原因（{@code null}=正常）。平台租户短路放行。 */
	public String validate(String code) {
		if (code == null || TenantConstants.DEFAULT_TENANT_ID.equals(code)) {
			return null;
		}
		return invalidReason(loadTenant(code));
	}

	/** 纯校验：由已加载的租户对象判定不可用原因（供拦截器复用缓存对象，避免重复查询）。 */
	public static String invalidReason(SysTenant tenant) {
		if (tenant == null) {
			return "租户不存在";
		}
		if (Integer.valueOf(0).equals(tenant.getStatus())) {
			return "租户已停用";
		}
		if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
			return "租户已过期";
		}
		return null;
	}

	/**
	 * 建用户前的账号配额校验：统计租户下活跃用户数与 account_count 比较，超额抛异常。
	 * 平台租户与未设上限（&lt;0）不限制；统计经 Flex 按该租户自动隔离 + 逻辑删除过滤。
	 * 并发安全由 {@link #quotaLocked} 保证（检查+插入同一锁内），本方法只负责判定。
	 */
	public void assertAccountQuota(String code) {
		if (code == null || TenantConstants.DEFAULT_TENANT_ID.equals(code)) {
			return;
		}
		SysTenant tenant = TenantContext.ignore(() ->
			tenantMapper.selectOneByQuery(QueryWrapper.create().eq("tenant_code", code)));
		if (tenant == null || tenant.getAccountCount() == null || tenant.getAccountCount() < 0) {
			return;
		}
		long used = TenantContext.execute(code, () -> userMapper.selectCountByQuery(QueryWrapper.create()));
		if (used >= tenant.getAccountCount()) {
			throw new ServiceException("租户账号数已达上限（" + tenant.getAccountCount() + "），请联系管理员开通");
		}
	}

	/**
	 * 租户级互斥执行（Redis SETNX 锁，集群安全）：配额「检查+插入」等读-改-写序列在同一锁内串行，
	 * 杜绝并发 TOCTOU 超额。平台租户无需锁直接执行。
	 */
	public <T> T quotaLocked(String code, java.util.function.Supplier<T> action) {
		if (code == null || TenantConstants.DEFAULT_TENANT_ID.equals(code)) {
			return action.get();
		}
		String key = "mugsun:quota:lock:" + code;
		String token = cn.hutool.core.util.IdUtil.fastSimpleUUID();
		Boolean acquired = redis.opsForValue().setIfAbsent(key, token, java.time.Duration.ofSeconds(10));
		if (!Boolean.TRUE.equals(acquired)) {
			throw new ServiceException("该租户正在创建账号，请稍后重试");
		}
		try {
			return action.get();
		} finally {
			String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
			redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
				java.util.List.of(key), token);
		}
	}

	/** 带过期时间的缓存条目 */
	private static final class Cached<T> {
		private final T value;
		private final long expireAt;

		Cached(T value) {
			this.value = value;
			this.expireAt = System.currentTimeMillis() + TTL_MILLIS;
		}

		boolean fresh() {
			return System.currentTimeMillis() < expireAt;
		}
	}
}
