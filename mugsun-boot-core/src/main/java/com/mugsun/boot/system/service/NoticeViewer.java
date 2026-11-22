package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 谁在看公告。一条公告对一个人可不可见，只取决于两样：他的用户编号，和他有没有公告管理权限（有的话全部可见）。
 *
 * <p>把这两样当参数传，读公告的代码就不用自己读登录态，也就能在没有登录用户的地方跑：
 * 微服务版里公告在工作台服务，别的服务经内部接口来取，那边只收得到这两样。
 *
 * @param userId 用户编号
 * @param manage 有没有公告管理权限
 */
public record NoticeViewer(long userId, boolean manage) {

	/** 公告管理权限码：有它的人不受可见范围限制 */
	public static final String PERM_MANAGE = "sys:notice:manage";

	/**
	 * 当前登录的人。只能在处理用户请求的地方调用；传给别处时传的是这里取到的值，不能拿前端传来的编号拼一个。
	 */
	public static NoticeViewer ofLogin() {
		return new NoticeViewer(StpUtil.getLoginIdAsLong(), StpUtil.hasPermission(PERM_MANAGE));
	}
}
