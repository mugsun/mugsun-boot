package com.mugsun.boot.ai.springai;

import com.mugsun.boot.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话选项记住发起请求时的租户，工具跟着带上；没有租户时什么都不带，也不沿用别的请求的。
 */
class MugsunChatOptionsTest {

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	/** 记下被调用时的租户 */
	private static final class ProbeTool implements ToolCallback {

		final List<String> tenants = new CopyOnWriteArrayList<>();

		@Override
		public ToolDefinition getToolDefinition() {
			return ToolDefinition.builder().name("probe").description("记下被调用时的租户")
				.inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
		}

		@Override
		public String call(String toolInput) {
			tenants.add(String.valueOf(TenantContext.current()));
			return "ok";
		}
	}

	@Test
	@DisplayName("改选项后再生成：各项的值和租户都保留，和原来的相等")
	void mutateKeepsValuesAndTenant() {
		MugsunChatOptions options = MugsunChatOptions.builder().tenantId("T1").model("m").temperature(0.3)
			.maxTokens(100).topP(0.9).topK(5).frequencyPenalty(0.1).presencePenalty(0.2)
			.stopSequences(List.of("。")).toolContext(Map.of("k", "v")).build();

		MugsunChatOptions copy = options.mutate().build();

		assertThat(copy).isEqualTo(options).hasSameHashCodeAs(options);
		assertThat(copy.tenantId()).isEqualTo("T1");
		assertThat(copy.getModel()).isEqualTo("m");
		assertThat(copy.getTemperature()).isEqualTo(0.3);
		assertThat(copy.getMaxTokens()).isEqualTo(100);
		assertThat(copy.getTopP()).isEqualTo(0.9);
		assertThat(copy.getTopK()).isEqualTo(5);
		assertThat(copy.getFrequencyPenalty()).isEqualTo(0.1);
		assertThat(copy.getPresencePenalty()).isEqualTo(0.2);
		assertThat(copy.getStopSequences()).containsExactly("。");
		assertThat(copy.getToolContext()).containsEntry("k", "v");
		assertThat(options.mutate().temperature(0.8).build()).isNotEqualTo(options);
	}

	@Test
	@DisplayName("合并调用方给的选项：对方设了的用对方的，没设的留原值，租户不变")
	void combineWithKeepsTenant() {
		MugsunChatOptions base = MugsunChatOptions.builder().tenantId("T1").temperature(0.1).maxTokens(50).build();

		MugsunChatOptions merged = base.mutate()
			.combineWith(ChatOptions.builder().temperature(0.7).model("快的那个")).build();

		assertThat(merged.tenantId()).isEqualTo("T1");
		assertThat(merged.getTemperature()).isEqualTo(0.7);
		assertThat(merged.getMaxTokens()).isEqualTo(50);
		assertThat(merged.getModel()).isEqualTo("快的那个");
	}

	@Test
	@DisplayName("工具带上发起请求时的租户：没有租户的线程上按它执行；线程自己有租户时以线程的为准")
	void toolCallbacksRunInCapturedTenant() {
		ProbeTool probe = new ProbeTool();
		MugsunChatOptions options = MugsunChatOptions.builder().tenantId("T1").toolCallbacks(List.of(probe)).build();
		ToolCallback bound = options.getToolCallbacks().get(0);

		bound.call("{}");
		TenantContext.set("T2");
		bound.call("{}");
		TenantContext.clear();
		bound.call("{}", null);

		assertThat(probe.tenants).containsExactly("T1", "T2", "T1");
		assertThat(TenantContext.getRaw()).as("执行完恢复原样").isNull();
		assertThat(bound.getToolDefinition().name()).isEqualTo("probe");
	}

	@Test
	@DisplayName("工具只包一层：选项改了再生成、换了租户，里面都还是原来的工具")
	void toolCallbacksAreWrappedOnce() {
		ProbeTool probe = new ProbeTool();
		MugsunChatOptions first = MugsunChatOptions.builder().tenantId("T1").toolCallbacks(List.of(probe)).build();

		ToolCallback rebound = first.mutate().tenantId("T2").build().getToolCallbacks().get(0);

		assertThat(rebound).isInstanceOf(TenantBoundToolCallback.class);
		assertThat(((TenantBoundToolCallback) rebound).delegate()).isSameAs(probe);
		rebound.call("{}");
		assertThat(probe.tenants).containsExactly("T2");
	}

	@Test
	@DisplayName("这次没有租户：工具不包，以前包进去的租户也去掉，不沿用别的请求的")
	void noTenantMeansNoBinding() {
		ProbeTool probe = new ProbeTool();
		MugsunChatOptions withTenant = MugsunChatOptions.builder().tenantId("T1").toolCallbacks(List.of(probe)).build();

		MugsunChatOptions without = withTenant.mutate().tenantId(null).build();

		assertThat(without.tenantId()).isNull();
		assertThat(without.getToolCallbacks().get(0)).isSameAs(probe);
		assertThat(MugsunChatOptions.builder().toolCallbacks(List.of(probe)).build().getToolCallbacks().get(0))
			.isSameAs(probe);
	}

	@Test
	@DisplayName("租户不同的两份选项不相等")
	void tenantIsPartOfEquality() {
		assertThat(MugsunChatOptions.builder().tenantId("T1").model("m").build())
			.isEqualTo(MugsunChatOptions.builder().tenantId("T1").model("m").build())
			.isNotEqualTo(MugsunChatOptions.builder().tenantId("T2").model("m").build())
			.isNotEqualTo(MugsunChatOptions.builder().model("m").build());
	}

	@Test
	@DisplayName("租户范围：线程没有租户时按给的租户执行，执行完恢复；线程有租户或显式不限租户时不改它")
	void tenantScope() {
		assertThat(TenantScope.present()).isFalse();
		assertThat(TenantScope.run("T1", TenantContext::current)).isEqualTo("T1");
		assertThat(TenantContext.getRaw()).isNull();
		assertThat(TenantScope.run(null, TenantContext::current)).isNull();

		Supplier<String> whileIgnoring = () -> TenantScope.run("T1", TenantContext::getRaw);
		assertThat(TenantContext.ignore(whileIgnoring)).isEqualTo(TenantContext.IGNORE);

		TenantContext.set("T2");
		assertThat(TenantScope.present()).isTrue();
		assertThat(TenantScope.run("T1", TenantContext::current)).isEqualTo("T2");
	}
}
