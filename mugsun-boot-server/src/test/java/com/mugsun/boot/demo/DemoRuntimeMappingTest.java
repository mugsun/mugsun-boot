package com.mugsun.boot.demo;

import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全复审 F6：用运行时 {@link RequestMappingHandlerMapping} 交叉核对静态扫描。
 * <ul>
 *   <li>运行时注册的每个写映射（含第三方 jar、动态注册）都必须被只读策略拒绝，白名单除外；</li>
 *   <li>运行时 com.mugsun 处理器的每个映射都必须出现在静态扫描结果里（否则静态测试和端点清单不完整）。</li>
 * </ul>
 */
class DemoRuntimeMappingTest extends AbstractIntegrationTest {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");
	private static final DemoReadOnlyPolicy POLICY = new DemoReadOnlyPolicy(true);

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mapping;

	@Test
	void runtimeMappingsAreDeniedAndCoveredByStaticScan() throws Exception {
		Set<String> staticSet = DemoReadOnlyEndpointsTest.staticMappings();
		List<String> leaks = new ArrayList<>();
		List<String> missingFromScan = new ArrayList<>();
		int writes = 0;
		for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
			RequestMappingInfo info = e.getKey();
			HandlerMethod hm = e.getValue();
			Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
			List<String> methods = new ArrayList<>();
			if (declared.isEmpty()) {
				for (RequestMethod m : RequestMethod.values()) {
					if (m != RequestMethod.TRACE) {
						methods.add(m.name());
					}
				}
			} else {
				declared.forEach(m -> methods.add(m.name()));
			}
			boolean ours = hm.getBeanType().getName().startsWith("com.mugsun.");
			for (String path : info.getPatternValues()) {
				for (String m : methods) {
					if (ours && !staticSet.contains(m + " " + path)) {
						missingFromScan.add(m + " " + path + " (" + hm.getBeanType().getSimpleName() + ")");
					}
					if (!WRITE_METHODS.contains(m)) {
						continue;
					}
					writes++;
					boolean allowed = POLICY.isAllowed(m, concrete(path), false);
					boolean expected = "POST".equals(m) && POLICY.writeAllowlist().contains(path);
					if (allowed != expected) {
						leaks.add(m + " " + path + " (" + hm.getBeanType().getName() + "#" + hm.getMethod().getName() + ")");
					}
				}
			}
		}
		assertThat(writes).as("运行时写映射数量").isGreaterThan(200);
		assertThat(leaks).as("运行时写映射放行与白名单不一致").isEmpty();
		assertThat(missingFromScan).as("运行时映射不在静态扫描里").isEmpty();
	}

	private static String concrete(String path) {
		return path.replaceAll("\\{[^}]*}", "1").replace("**", "x").replace("*", "x");
	}
}
