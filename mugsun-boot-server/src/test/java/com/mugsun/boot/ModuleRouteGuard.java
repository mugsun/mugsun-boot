package com.mugsun.boot;

import com.mugsun.boot.common.module.ModuleGateConfig;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 模块接口覆盖守卫的函数式路由部分：把 RouterFunction 拍平成「handler 所在包 + 完整路径」，再按模块闸门前缀检查。
 * <p>独立成普通类（不继承 AbstractIntegrationTest），守卫逻辑本身由 {@code ModuleRouteGuardTest} 在无容器环境下自检；
 * {@code ModuleGateApiTest} 用它检查真实上下文里的全部 RouterFunction 路由。没有 Spring 注解，不会被组件扫描注册。
 */
public final class ModuleRouteGuard {

	private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

	static final Map<String, String[]> GATES = Map.of(
		"com.mugsun.boot.ai", ModuleGateConfig.AI_PATHS,
		"com.mugsun.boot.gis", ModuleGateConfig.GIS_PATHS,
		"com.mugsun.boot.track", ModuleGateConfig.TRACK_PATHS);

	private ModuleRouteGuard() {
	}

	/** 一条函数式路由：完整路径模式（含嵌套前缀；解析不出路径时为 null）与 handler 所在包 */
	public record FnRoute(String owner, String path) {
	}

	public static List<String> violations(List<FnRoute> routes) {
		List<String> out = new ArrayList<>();
		for (FnRoute r : routes) {
			String owner = r.owner();
			String path = r.path();
			if (owner.startsWith("org.springframework.ai") || owner.startsWith("io.modelcontextprotocol")
				|| (path != null && (path.equals("/sse") || path.startsWith("/sse/")
					|| path.equals("/mcp") || path.startsWith("/mcp/")))) {
				out.add("暂时下线（加鉴权后恢复）的 MCP / Spring AI 路由：" + owner + " " + path);
				continue;
			}
			for (Map.Entry<String, String[]> gate : GATES.entrySet()) {
				if (!owner.equals(gate.getKey()) && !owner.startsWith(gate.getKey() + ".")) {
					continue;
				}
				if (path == null) {
					out.add("解析不出路径（按未覆盖处理）：" + owner);
					continue;
				}
				PathContainer container = PathContainer.parsePath(path);
				if (Arrays.stream(gate.getValue()).map(PARSER::parse).noneMatch(gp -> gp.matches(container))) {
					out.add("不在闸门内：" + owner + " " + path);
				}
			}
		}
		return out;
	}

	/** 把 RouterFunction 树拍平；嵌套谓词里的路径作为前缀拼到子路由上 */
	public static List<FnRoute> flatten(RouterFunction<?> fn) {
		List<FnRoute> out = new ArrayList<>();
		Deque<List<String>> prefixes = new ArrayDeque<>();
		fn.accept(new RouterFunctions.Visitor() {
			@Override
			public void startNested(RequestPredicate predicate) {
				prefixes.push(paths(predicate));
			}

			@Override
			public void endNested(RequestPredicate predicate) {
				prefixes.pop();
			}

			@Override
			public void route(RequestPredicate predicate, HandlerFunction<?> handlerFunction) {
				String owner = handlerFunction.getClass().getPackageName();
				List<String> full = new ArrayList<>(List.of(""));
				List<List<String>> levels = new ArrayList<>(prefixes);
				Collections.reverse(levels);
				levels.add(paths(predicate));
				for (List<String> level : levels) {
					if (level.isEmpty()) {
						continue;
					}
					List<String> next = new ArrayList<>();
					for (String base : full) {
						for (String p : level) {
							next.add(base.isEmpty() ? p : PARSER.parse(base).combine(PARSER.parse(p)).getPatternString());
						}
					}
					full = next;
				}
				for (String p : full) {
					out.add(new FnRoute(owner, p.isEmpty() ? null : p));
				}
			}

			@Override
			public void resources(Function<ServerRequest, Optional<Resource>> lookupFunction) {
				out.add(new FnRoute(lookupFunction.getClass().getPackageName(), null));
			}

			@Override
			public void attributes(Map<String, Object> attributes) {
			}

			@Override
			public void unknown(RouterFunction<?> routerFunction) {
				out.add(new FnRoute(routerFunction.getClass().getPackageName(), null));
			}
		});
		return out;
	}

	/** 谓词里出现的全部路径模式（与、或都收集，宁多勿漏） */
	private static List<String> paths(RequestPredicate predicate) {
		List<String> found = new ArrayList<>();
		predicate.accept(new RequestPredicates.Visitor() {
			@Override
			public void method(Set<HttpMethod> methods) {
			}

			@Override
			public void path(String pattern) {
				found.add(pattern);
			}

			@Override
			public void pathExtension(String extension) {
			}

			@Override
			public void header(String name, String value) {
			}

			@Override
			public void param(String name, String value) {
			}

			@Override
			public void startAnd() {
			}

			@Override
			public void and() {
			}

			@Override
			public void endAnd() {
			}

			@Override
			public void startOr() {
			}

			@Override
			public void or() {
			}

			@Override
			public void endOr() {
			}

			@Override
			public void startNegate() {
			}

			@Override
			public void endNegate() {
			}

			@Override
			public void unknown(RequestPredicate predicate) {
			}
		});
		return found;
	}

}
