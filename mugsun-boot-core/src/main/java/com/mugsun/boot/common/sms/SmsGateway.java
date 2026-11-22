package com.mugsun.boot.common.sms;

/**
 * 短信出口：登录、双因子、消息通知要发短信、验验证码时只认这个接口，不直接依赖短信的实现类。
 * <p>单体里由短信服务类实现。微服务版里短信在单独的服务上，别的服务拿到的是转发到那边的实现，
 * 所以调用方不能假设这些方法在本进程里执行：没发出去、对面连不上时一律抛
 * {@link com.mugsun.core.tool.exception.ServiceException}，不会当成成功返回。
 */
public interface SmsGateway {

	/**
	 * 发登录验证码：同号 60 秒一条（防短信轰炸），发新作废旧（任意时刻只有一条有效）。
	 * 没有启用的短信配置、没发出去都抛错，并作废刚写入的验证码。
	 */
	void sendCode(String phone);

	/** 是否把验证码回显给页面（开发联调用，生产关闭） */
	boolean isShowCode();

	/** 取该手机号最近一条未过期验证码（只给开发回显用，不能拿它做校验） */
	String peekCode(String phone);

	/** 校验验证码：对上未过期的那一条就通过，并把它作废 */
	boolean verifyCode(String phone, String code);

	/** 发一条文本短信（双因子、消息通知用）：没有启用的短信配置或没发出去就抛错 */
	void sendText(String phone, String text);

	/** 号码规范化（发码、频控、核验统一口径）：去空白与 - ，去 +86/0086/86 前缀；空则 null */
	static String normalizePhone(String phone) {
		if (phone == null) {
			return null;
		}
		String p = phone.replaceAll("[\\s-]", "");
		if (p.startsWith("+86")) {
			p = p.substring(3);
		} else if (p.startsWith("0086")) {
			p = p.substring(4);
		} else if (p.startsWith("86") && p.length() == 13) {
			p = p.substring(2);
		}
		return p.isEmpty() ? null : p;
	}
}
