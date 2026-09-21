package com.mugsun.boot.security;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JDBC URL 注入/RCE 校验：保护独立数据源（G55/G71）与动态建表（G78）不被恶意 JDBC 连接串利用。
 * <p>拦截多方言高危连接参数（文件读写/反序列化/类加载 RCE 面）：MySQL 的 allowLoadLocalInfile/autoDeserialize/
 * queryInterceptors/detectCustomCollations、PostgreSQL 的 socketFactory/sslfactory/loggerFile、通用 socketFactory 等；
 * 并封禁内嵌库 scheme（h2/hsqldb/derby/sqlite 的 INIT/RUNSCRIPT/load_extension 面）。
 */
public final class JdbcUrlValidator {

	/** 高危参数键（小写精确匹配） */
	private static final Set<String> DENY_KEYS = Set.of(
		"autodeserialize", "allowloadlocalinfile", "allowlocalinfile", "allowurlinlocalinfile", "uselocalinfile",
		"queryinterceptors", "statementinterceptors", "exceptioninterceptors", "detectcustomcollations",
		"allowmultiqueries", "socketfactory", "socketfactoryarg", "serverrsapublickeyfile",
		"clientcertificatekeystoreurl", "propertiestransform", "sslfactory", "sslfactoryarg",
		"sslhostnameverifier", "sslpasswordcallback", "loggerfile", "init");

	/** 高危参数键子串（捕获变体/大小写混淆） */
	private static final List<String> DENY_KEY_SUBSTR = List.of(
		"deserial", "localinfile", "interceptor", "socketfactory", "runscript", "propertiestransform");

	/** 封禁的内嵌库 scheme（RUNSCRIPT/文件加载 RCE 面） */
	private static final Set<String> DENY_SCHEMES = Set.of("h2", "hsqldb", "derby", "sqlite", "log4jdbc");

	private JdbcUrlValidator() {
	}

	/** 校验 JDBC URL；含高危参数/封禁 scheme 抛异常。返回原 URL 便于链式使用。 */
	public static String validate(String url) {
		if (url == null || url.isBlank()) {
			throw new ServiceException("数据源连接串不能为空");
		}
		String u = url.trim();
		// 先做 URL 解码再校验，防 %61utoDeserialize 之类百分号编码绕过黑名单（驱动会解码）
		String decoded = decode(u);
		String lower = decoded.toLowerCase();
		if (!lower.startsWith("jdbc:")) {
			throw new ServiceException("非法数据源连接串（须以 jdbc: 开头）");
		}
		int schemeEnd = lower.indexOf(':', 5);
		String scheme = schemeEnd > 0 ? lower.substring(5, schemeEnd) : lower.substring(5);
		if (DENY_SCHEMES.contains(scheme)) {
			throw new ServiceException("禁止使用内嵌库连接串（存在 RCE 风险）：" + scheme);
		}
		for (String key : paramKeys(decoded)) {
			String k = key.toLowerCase();
			if (DENY_KEYS.contains(k)) {
				throw new ServiceException("数据源连接串含高危参数：" + key);
			}
			for (String bad : DENY_KEY_SUBSTR) {
				if (k.contains(bad)) {
					throw new ServiceException("数据源连接串含高危参数：" + key);
				}
			}
		}
		return u;
	}

	/** 尽力 URL 解码（多轮，抵御多层编码）；解码失败按原串处理 */
	private static String decode(String s) {
		String cur = s;
		for (int i = 0; i < 3 && cur.indexOf('%') >= 0; i++) {
			try {
				String next = java.net.URLDecoder.decode(cur, java.nio.charset.StandardCharsets.UTF_8);
				if (next.equals(cur)) {
					break;
				}
				cur = next;
			} catch (Exception e) {
				break;
			}
		}
		return cur;
	}

	/** 提取参数键：'?' 后 '&' 分隔的查询参数 + 首个 ';' 后 ';'/'&' 分隔的属性（覆盖 MySQL/PG/达梦/金仓） */
	private static List<String> paramKeys(String url) {
		List<String> keys = new ArrayList<>();
		StringBuilder params = new StringBuilder();
		int q = url.indexOf('?');
		if (q >= 0) {
			params.append(url.substring(q + 1));
		}
		int semi = url.indexOf(';');
		if (semi >= 0) {
			params.append('&').append(url.substring(semi + 1));
		}
		for (String seg : params.toString().split("[&;]")) {
			int eq = seg.indexOf('=');
			String k = (eq >= 0 ? seg.substring(0, eq) : seg).trim();
			if (!k.isEmpty()) {
				keys.add(k);
			}
		}
		return keys;
	}
}
