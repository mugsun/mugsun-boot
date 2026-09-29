package com.mugsun.boot.common.module;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可选模块缺省 Bean：核心在未引入 gis/track/ai 模块时仍能启动。
 */
@Configuration
public class OptionalModuleConfiguration {

	@Bean
	@ConditionalOnMissingBean(GisModuleStatus.class)
	public GisModuleStatus disabledGisModuleStatus() {
		return new GisModuleStatus() {
			@Override
			public boolean present() {
				return false;
			}

			@Override
			public boolean enabled() {
				return false;
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean(TrackModuleStatus.class)
	public TrackModuleStatus disabledTrackModuleStatus() {
		return new TrackModuleStatus() {
			@Override
			public boolean present() {
				return false;
			}

			@Override
			public boolean enabled() {
				return false;
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean(AiModuleStatus.class)
	public AiModuleStatus disabledAiModuleStatus() {
		return new AiModuleStatus() {
			@Override
			public boolean present() {
				return false;
			}

			@Override
			public boolean enabled() {
				return false;
			}
		};
	}
}
