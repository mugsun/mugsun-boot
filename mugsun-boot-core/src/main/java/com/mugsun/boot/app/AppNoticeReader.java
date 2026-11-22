package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.system.service.NoticeViewer;

/**
 * 取一个用户在首页要看的公告：还有几条没读，和最近的几条。
 *
 * <p>单体版由 {@link AppNoticeService} 实现，直接查公告的表。单独定义成接口的原因和 {@link AppTodoReader} 一样：
 * 微服务版里公告在工作台服务，首页不在那个服务里，那边换成经内部接口去取的实现，首页的代码两边是同一份。
 */
public interface AppNoticeReader {

	/**
	 * 这个人看得到的公告里，没读的条数和最近的几条（置顶在前、新的在前），租户取当前上下文的。
	 *
	 * <p>实现不看登录态，看的人由调用方给，见 {@link NoticeViewer#ofLogin()}。
	 *
	 * @param limit 最近的公告最多取几条
	 */
	AppNoticeSummaryVO summaryOf(NoticeViewer viewer, int limit);
}
