package com.mugsun.boot.system.service;

import java.util.List;

/**
 * 按标题找公告，全局搜索用。
 *
 * <p>单体版由 {@link NoticeReadService} 实现，直接查公告的表。单独定义成接口，是因为微服务版里公告在工作台服务，
 * 全局搜索不在那个服务里：那边换成经内部接口去取的实现，搜索的代码两边是同一份。
 */
public interface NoticeTitleFinder {

	/**
	 * 标题里带这个词、这个人看得到的公告的标题，新的在前，租户取当前上下文的。词是空的就什么都不找。
	 *
	 * <p>实现不看登录态，看的人由调用方给，见 {@link NoticeViewer#ofLogin()}。
	 *
	 * @param keyword 要找的词，不带通配符
	 * @param limit   最多几条
	 */
	List<String> titlesLike(NoticeViewer viewer, String keyword, int limit);
}
