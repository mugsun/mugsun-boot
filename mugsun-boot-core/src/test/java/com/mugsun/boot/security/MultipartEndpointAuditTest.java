package com.mugsun.boot.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 审计用假 HandlerMethod：识别各种 multipart 写法，漏配打 WARN、多配打 INFO、路径变量打 ERROR */
@ExtendWith(OutputCaptureExtension.class)
class MultipartEndpointAuditTest {

	record Dto(String name) {
	}

	@SuppressWarnings("unused")
	static class FakeController {
		public void file(MultipartFile file) {
		}
		public void files(List<MultipartFile> files) {
		}
		public void part(@RequestPart("meta") Dto meta) {
		}
		public void consumesOnly(String name) {
		}
		public void rawGetParts(HttpServletRequest request) {
		}
		public void json(Dto body) {
		}
	}

	private static HandlerMethod hm(String name, Class<?>... types) throws Exception {
		return new HandlerMethod(new FakeController(), FakeController.class.getMethod(name, types));
	}

	private static MultipartEndpointAudit audit(Map<RequestMappingInfo, HandlerMethod> methods, List<String> allowed) {
		RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
		when(mapping.getHandlerMethods()).thenReturn(methods);
		MultipartGuardProperties props = new MultipartGuardProperties();
		props.setAllowedPaths(allowed);
		return new MultipartEndpointAudit(mapping, props);
	}

	private static Map<RequestMappingInfo, HandlerMethod> fixture() throws Exception {
		Map<RequestMappingInfo, HandlerMethod> m = new LinkedHashMap<>();
		m.put(RequestMappingInfo.paths("/a/file").build(), hm("file", MultipartFile.class));
		m.put(RequestMappingInfo.paths("/a/files").build(), hm("files", List.class));
		m.put(RequestMappingInfo.paths("/a/part").build(), hm("part", Dto.class));
		m.put(RequestMappingInfo.paths("/a/consumes").consumes("multipart/form-data").build(),
			hm("consumesOnly", String.class));
		m.put(RequestMappingInfo.paths("/a/mixed").consumes("MULTIPART/MIXED").build(),
			hm("consumesOnly", String.class));
		m.put(RequestMappingInfo.paths("/a/raw").build(), hm("rawGetParts", HttpServletRequest.class));
		m.put(RequestMappingInfo.paths("/a/json").consumes("application/json").build(), hm("json", Dto.class));
		m.put(RequestMappingInfo.paths("/a/{id}/upload").build(), hm("file", MultipartFile.class));
		return m;
	}

	@Test
	@DisplayName("识别：文件参数、集合、@RequestPart、consumes=multipart/*；方法体里调 getParts 的识别不了")
	void detectsMultipartEndpoints() throws Exception {
		assertThat(audit(fixture(), List.of()).multipartEndpoints()).containsExactlyInAnyOrder(
			"/a/file", "/a/files", "/a/part", "/a/consumes", "/a/mixed", "/a/{id}/upload");
	}

	@Test
	@DisplayName("漏配打 WARN，多配打 INFO，带路径变量打 ERROR")
	void logsMissingExtraAndPathVariable(CapturedOutput output) throws Exception {
		audit(fixture(), List.of("/a/file", "/a/files", "/a/part", "/a/mixed", "/a/gone")).audit();
		assertThat(output.getOut()).containsPattern("WARN .*接口 /a/consumes 接收文件，但不在");
		assertThat(output.getOut()).doesNotContainPattern("WARN .*接口 /a/file 接收文件");
		assertThat(output.getOut()).containsPattern("INFO .*allowed-paths 里的 /a/gone 当前没有对应的上传接口");
		assertThat(output.getOut()).containsPattern("ERROR .*接口 /a/\\{id\\}/upload 接收文件但带路径变量");
	}
}
