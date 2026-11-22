package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppNoticeDetailVO;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.app.dto.AppPageVO;
import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.service.NoticeReadService;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.core.i18n.message.I18n;
import com.mybatisflex.core.paginate.Page;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 手机端的公告：列表、详情、标记已读，和首页要的未读数、最近几条。
 *
 * <p>查库和可见范围都在 {@link NoticeReadService}，这里只把结果转成手机端要的样子。
 */
@Service
public class AppNoticeService implements AppNoticeReader {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/** 手机端一页最多给多少条 */
	private static final long MAX_PAGE_SIZE = 50;

	private final NoticeReadService notices;

	public AppNoticeService(NoticeReadService notices) {
		this.notices = notices;
	}

	public AppPageVO<AppNoticeItemVO> notices(long pageNum, long pageSize) {
		Page<SysNotice> page = notices.page(NoticeViewer.ofLogin(), pageNum, Math.min(pageSize, MAX_PAGE_SIZE));
		return new AppPageVO<>(items(page.getRecords()), page.getTotalRow());
	}

	public AppNoticeDetailVO notice(Long id) {
		SysNotice n = notices.detail(NoticeViewer.ofLogin(), id);
		return new AppNoticeDetailVO(
			n.getId(),
			blank(n.getTitle(), I18n.translate("未命名公告")),
			blank(n.getContent(), ""),
			fmt(n.getReleaseTime() != null ? n.getReleaseTime() : n.getCreateTime()),
			!Boolean.TRUE.equals(n.getReadFlag())
		);
	}

	public void readNotice(Long noticeId) {
		notices.markRead(NoticeViewer.ofLogin(), noticeId);
	}

	@Override
	public AppNoticeSummaryVO summaryOf(NoticeViewer viewer, int limit) {
		return new AppNoticeSummaryVO(notices.unreadCount(viewer), items(notices.recent(viewer, limit)));
	}

	private static List<AppNoticeItemVO> items(List<SysNotice> list) {
		return list.stream().map(n -> new AppNoticeItemVO(
			n.getId(),
			blank(n.getTitle(), I18n.translate("未命名公告")),
			fmt(n.getReleaseTime() != null ? n.getReleaseTime() : n.getCreateTime()),
			!Boolean.TRUE.equals(n.getReadFlag())
		)).toList();
	}

	private static String fmt(LocalDateTime t) {
		return t == null ? "" : TIME.format(t);
	}

	private static String blank(String v, String fallback) {
		return v == null || v.isBlank() ? fallback : v;
	}
}
