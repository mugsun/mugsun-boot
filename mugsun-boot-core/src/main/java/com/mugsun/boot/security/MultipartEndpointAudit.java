package com.mugsun.boot.security;

import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动自检：从 MVC 映射推导「哪些接口需要 multipart」，与 {@link MultipartGuardProperties} 白名单对照。
 * 白名单仍以配置为准，推导结果只用于发现漏配（WARN）或多配（INFO）。
 * <p>能识别的写法：
 * <ul>
 *   <li>参数类型是 MultipartFile、Part、MultipartRequest，或它们的数组、集合；</li>
 *   <li>参数带 {@code @RequestPart}（含绑定 DTO 的写法）；</li>
 *   <li>映射的 {@code consumes} 含 {@code multipart/*}，即使没有文件参数。</li>
 * </ul>
 * <p>识别不了：在方法体里直接调 {@code HttpServletRequest#getParts()} / {@code getPart()} 的接口——这只能读字节码，
 * 静态推导做不到。这类接口必须手工加进白名单，否则 multipart 请求会被 415。
 * <p>带路径变量的模式（如 {@code /x/{id}/upload}）无法配进白名单（启动校验拒绝 { }），这里打 ERROR 说明原因。
 */
@Component
public class MultipartEndpointAudit {

	private static final Logger log = LoggerFactory.getLogger(MultipartEndpointAudit.class);

	private final RequestMappingHandlerMapping mapping;
	private final MultipartGuardProperties properties;

	public MultipartEndpointAudit(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping,
		MultipartGuardProperties properties) {
		this.mapping = mapping;
		this.properties = properties;
	}

	/** 识别出的 multipart 接口路径（模式原文） */
	public Set<String> multipartEndpoints() {
		Set<String> paths = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
			if (consumesMultipart(e.getKey()) || takesMultipart(e.getValue())) {
				paths.addAll(e.getKey().getPatternValues());
			}
		}
		return paths;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void audit() {
		Set<String> derived = multipartEndpoints();
		Set<String> allowed = new TreeSet<>(properties.getAllowedPaths());
		for (String p : derived) {
			if (p.contains("{")) {
				log.error("接口 {} 接收文件但带路径变量，无法加进 mugsun.security.multipart.allowed-paths（只支持精确路径），"
					+ "multipart 请求会被 415；请改成不带变量的路径", p);
			} else if (!allowed.contains(p)) {
				log.warn("接口 {} 接收文件，但不在 mugsun.security.multipart.allowed-paths 里，multipart 请求会被 415", p);
			}
		}
		for (String p : allowed) {
			if (!derived.contains(p)) {
				// 可选模块（如 track）未装配时正常出现，只记 INFO
				log.info("mugsun.security.multipart.allowed-paths 里的 {} 当前没有对应的上传接口（模块未装配或配错）", p);
			}
		}
	}

	static boolean consumesMultipart(RequestMappingInfo info) {
		for (MediaType t : info.getConsumesCondition().getConsumableMediaTypes()) {
			if ("multipart".equalsIgnoreCase(t.getType())) {
				return true;
			}
		}
		return false;
	}

	static boolean takesMultipart(HandlerMethod method) {
		for (MethodParameter p : method.getMethodParameters()) {
			if (p.hasParameterAnnotation(RequestPart.class)) {
				return true;
			}
			Class<?> type = p.getParameterType();
			if (MultipartRequest.class.isAssignableFrom(type) || isFileType(type)) {
				return true;
			}
			ResolvableType rt = ResolvableType.forMethodParameter(p);
			Class<?> element = type.isArray() ? type.getComponentType()
				: rt.hasGenerics() ? rt.getGeneric(rt.getGenerics().length - 1).resolve() : null;
			if (element != null && isFileType(element)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isFileType(Class<?> type) {
		return MultipartFile.class.isAssignableFrom(type) || Part.class.isAssignableFrom(type);
	}
}
