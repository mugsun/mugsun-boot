package com.mugsun.boot.common.crypto;

/**
 * 测试专用：把进程内的 {@link Sm4Util} 密钥恢复成集成测试密钥。
 * <p>Sm4Util 的密钥是 JVM 级静态状态。单测（如 Sm4KeyFormatTest）会用别的密钥调 init，
 * 而 Spring 测试上下文会被缓存复用、不会再初始化一次，于是后面的集成测试会用错密钥写库。
 * 集成测试基座在每个用例前调用这里，保证落库密文都用同一把测试密钥。
 */
public final class Sm4TestKeys {

	/** 与 AbstractIntegrationTest 的 mugsun.crypto.sm4-key 相同（公开测试值，不是真实密钥） */
	public static final String INTEGRATION_KEY = "6d756773756e2d746573742d736d346b";

	private Sm4TestKeys() {
	}

	public static void useIntegrationKey() {
		Sm4Util.init(INTEGRATION_KEY);
	}
}
