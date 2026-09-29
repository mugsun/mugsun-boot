package com.mugsun.boot.common.module;

import java.util.Map;

/**
 * 可选模块向 AI 编织提供的只读库内快照。
 * <p>未装配对应 jar 时没有这个 Bean。编织接口必须据此返回业务失败，不能改走模型编造图层或埋点数字。
 */
public interface ModuleInsightPort {

	/** {@code gis} 或 {@code track} */
	String module();

	/** 从本模块数据库取实数。模块关闭时抛业务异常。 */
	Map<String, Object> snapshot(Map<String, Object> request);
}
