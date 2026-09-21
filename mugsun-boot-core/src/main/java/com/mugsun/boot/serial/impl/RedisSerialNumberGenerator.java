package com.mugsun.boot.serial.impl;

import com.mugsun.boot.serial.AbstractSerialNumberGenerator;
import com.mugsun.boot.serial.GenerateResult;
import com.mugsun.boot.serial.SerialNumberInfo;
import com.mugsun.boot.serial.SerialRuleType;
import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.boot.serial.mapper.SysSerialNumberMapper;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 自增实现：key 内嵌周期串（跨周期天然重置），Lua 原子「首置初值 + 步长自增」消除首次竞态；
 * 每次生成刷新 TTL，周期结束后旧 key 随 TTL 自动回收，无需定时清理。
 */
@Component
public class RedisSerialNumberGenerator extends AbstractSerialNumberGenerator {

	private static final String KEY_PREFIX = "mugsun:serial:";

	/** 首次不存在则置初值为基线；当前值低于 DB 末值（floor）则抬升到 floor，再按步长和自增——
	 *  运行期 Redis 丢数（flush/无持久化重启）也不会从初值重发与 DB 高水位重号 */
	private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
		"local cur = redis.call('get', KEYS[1]) "
			+ "if cur == false then cur = ARGV[2] end "
			+ "if tonumber(cur) < tonumber(ARGV[3]) then cur = ARGV[3] end "
			+ "redis.call('set', KEYS[1], cur) "
			+ "return redis.call('incrby', KEYS[1], ARGV[1])", Long.class);

	/** 预热：仅当 key 缺失或小于 DB 末值时补齐，避免重启/切换 provider 后号倒退 */
	private static final DefaultRedisScript<Long> WARMUP_SCRIPT = new DefaultRedisScript<>(
		"local cur = redis.call('get', KEYS[1]) "
			+ "if cur == false or tonumber(cur) < tonumber(ARGV[1]) then redis.call('set', KEYS[1], ARGV[1]) end "
			+ "return 1", Long.class);

	private static final Logger log = LoggerFactory.getLogger(RedisSerialNumberGenerator.class);

	private final StringRedisTemplate redisTemplate;

	public RedisSerialNumberGenerator(SysSerialNumberMapper serialMapper, StringRedisTemplate redisTemplate) {
		super(serialMapper);
		this.redisTemplate = redisTemplate;
	}

	/** 启动预热：把当前周期各规则的 DB 末值恢复到 Redis，重启或从 db 切回不倒退 */
	@PostConstruct
	void warmUp() {
		for (SysSerialNumber rule : serialMapper.selectAll()) {
			if (rule.getLastNumber() == null || rule.getLastTime() == null) {
				continue;
			}
			try {
				SerialNumberInfo info = SerialNumberInfo.parse(rule);
				if (needReset(rule.getLastTime(), info.ruleType())) {
					continue;
				}
				String key = key(info, LocalDate.now());
				redisTemplate.execute(WARMUP_SCRIPT, List.of(key), String.valueOf(rule.getLastNumber()));
				refreshTtl(key, info.ruleType());
			} catch (Exception e) {
				log.warn("单号规则预热跳过 code={}", rule.getCode(), e);
			}
		}
	}

	@Override
	public List<String> generate(SerialNumberInfo info, int count) {
		int[] steps = new int[count];
		long sum = 0;
		for (int i = 0; i < count; i++) {
			steps[i] = info.stepRandomRange() > 1 ? ThreadLocalRandom.current().nextInt(1, info.stepRandomRange() + 1) : 1;
			sum += steps[i];
		}

		String key = key(info, LocalDate.now());
		Long end = redisTemplate.execute(INCR_SCRIPT, List.of(key),
			String.valueOf(sum), String.valueOf(info.initNumber()), String.valueOf(dbFloor(info)));
		if (end == null) {
			throw new ServiceException("单号自增失败: " + info.code());
		}
		refreshTtl(key, info.ruleType());

		// 由末值倒推本批各号：末号=end，其前一号=当前号-本号步长
		List<Long> numberList = new ArrayList<>(count);
		long number = end;
		for (int i = count - 1; i >= 0; i--) {
			numberList.add(0, number);
			number -= steps[i];
		}

		GenerateResult result = new GenerateResult(info.code(), end, LocalDateTime.now(), numberList);
		persist(result, count, true);
		return format(result, info);
	}

	private String key(SerialNumberInfo info, LocalDate date) {
		String suffix = switch (info.ruleType()) {
			case DAY -> ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
			case MONTH -> ":" + date.format(DateTimeFormatter.ofPattern("yyyyMM"));
			case YEAR -> ":" + date.getYear();
			case NONE -> "";
		};
		return KEY_PREFIX + info.code() + suffix;
	}

	/** DB 高水位下限：同周期取 DB 末值，跨周期回初值（Redis 丢数时防与已落库高水位重号） */
	private long dbFloor(SerialNumberInfo info) {
		try {
			com.mybatisflex.core.row.Row row = com.mybatisflex.core.row.Db.selectOneBySql(
				"SELECT last_number, last_time FROM " + com.mugsun.boot.config.BizTables.of("sys_serial_number")
					+ " WHERE code = ? AND is_deleted = 0", info.code());
			if (row == null || row.getLong("last_number") == null) {
				return info.initNumber();
			}
			java.sql.Timestamp ts = row.getTimestamp("last_time");
			LocalDateTime lastTime = ts == null ? null : ts.toLocalDateTime();
			return needReset(lastTime, info.ruleType()) ? info.initNumber() : row.getLong("last_number");
		} catch (Exception e) {
			// 读库失败降级初值（Redis 正常路径不受影响）
			return info.initNumber();
		}
	}

	/** TTL 远超周期长度，杜绝周期内过期导致的号重复；NONE 无周期不设 TTL */
	private void refreshTtl(String key, SerialRuleType ruleType) {
		Duration ttl = switch (ruleType) {
			case DAY -> Duration.ofDays(3);
			case MONTH -> Duration.ofDays(40);
			case YEAR -> Duration.ofDays(400);
			case NONE -> null;
		};
		if (ttl != null) {
			redisTemplate.expire(key, ttl);
		}
	}

	@Override
	public String type() {
		return "redis";
	}
}
