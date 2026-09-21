package com.mugsun.boot.common.mask;

import com.mugsun.boot.common.constant.FieldMaskConstants;
import com.mybatisflex.core.mask.MaskManager;
import com.mybatisflex.core.mask.MaskProcessor;
import com.mybatisflex.core.mask.Masks;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 字段级权限装配：为受控敏感字段注册按角色决策的脱敏处理器，并挂载字段脱敏拦截器。
 * <p>处理器复用 Flex 内置脱敏算法作展示层，仅叠加"按权限码决定 明文/脱敏/不可见"的决策层。
 * 注册早于任何业务查询（{@code @PostConstruct} 在容器刷新期完成），故首次查询即生效。
 */
@Configuration
public class FieldMaskConfig implements WebMvcConfigurer {

	@PostConstruct
	public void registerProcessors() {
		MaskManager.registerMaskProcessor(FieldMaskConstants.TYPE_USER_PHONE, new RoleAwareMaskProcessor(
			FieldMaskConstants.PERM_USER_PHONE, FieldMaskConstants.PERM_USER_PHONE_PLAIN, builtin(Masks.MOBILE)));
		MaskManager.registerMaskProcessor(FieldMaskConstants.TYPE_USER_ID_CARD, new RoleAwareMaskProcessor(
			FieldMaskConstants.PERM_USER_ID_CARD, FieldMaskConstants.PERM_USER_ID_CARD_PLAIN, builtin(Masks.ID_CARD_NUMBER)));
	}

	/** 取 Flex 内置脱敏算法作展示层委托，缺失即配置错误，启动即暴露 */
	private MaskProcessor builtin(String type) {
		MaskProcessor processor = MaskManager.getProcessorMap().get(type);
		if (processor == null) {
			throw new IllegalStateException("缺少内置脱敏处理器：" + type);
		}
		return processor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new FieldMaskInterceptor())
			.addPathPatterns("/system/**")
			.order(Ordered.HIGHEST_PRECEDENCE + 30);
	}
}
