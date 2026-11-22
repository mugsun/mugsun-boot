package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppTodoItemVO;

import java.util.List;

/**
 * 取一个用户的待办列表。手机端首页用它显示待办数和前几条。
 *
 * <p>单体版由 {@link AppTodoService} 实现，直接查流程引擎的表。单独定义成接口，是因为微服务版里审批流是单独的服务，
 * 首页不在那个服务里：那边换成经内部接口去取的实现，首页的代码两边是同一份。
 */
public interface AppTodoReader {

	/**
	 * 指定用户的待办，新的在前，租户取当前上下文的。
	 *
	 * <p>实现不看登录态，调用方要传当前登录用户自己的编号，不能拿前端传来的编号来取。
	 */
	List<AppTodoItemVO> listOf(long userId);
}
