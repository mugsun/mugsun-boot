package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.service.NoticeReadService;
import com.mugsun.boot.system.service.NoticeViewer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 首页要的公告：未读数和最近几条都按传进来的「谁在看」去取，一条公告转成一条，顺序不变。不需要登录态，也不需要数据库。
 */
class AppNoticeServiceTest {

	private final NoticeReadService notices = mock(NoticeReadService.class);

	private final AppNoticeService service = new AppNoticeService(notices);

	@Test
	void summarisesForTheGivenViewer() {
		NoticeViewer viewer = new NoticeViewer(7L, false);
		when(notices.unreadCount(viewer)).thenReturn(2L);
		when(notices.recent(viewer, 3)).thenReturn(List.of(
			notice(12L, "停电通知", LocalDateTime.of(2026, 11, 22, 10, 5), null, false),
			notice(11L, "放假安排", null, LocalDateTime.of(2026, 11, 20, 9, 0), true)));

		AppNoticeSummaryVO summary = service.summaryOf(viewer, 3);

		assertThat(summary.unread()).isEqualTo(2L);
		assertThat(summary.recent()).containsExactly(
			new AppNoticeItemVO(12L, "停电通知", "2026-11-22 10:05", true),
			new AppNoticeItemVO(11L, "放假安排", "2026-11-20 09:00", false));
		verify(notices).unreadCount(viewer);
		verify(notices).recent(viewer, 3);
		verifyNoMoreInteractions(notices);
	}

	@Test
	void aNoticeWithoutTitleOrTimeStillShows() {
		NoticeViewer viewer = new NoticeViewer(7L, true);
		when(notices.recent(viewer, 3)).thenReturn(List.of(notice(12L, " ", null, null, null)));

		assertThat(service.summaryOf(viewer, 3).recent())
			.containsExactly(new AppNoticeItemVO(12L, "未命名公告", "", true));
	}

	@Test
	void noNoticesGivesZeroAndAnEmptyList() {
		NoticeViewer viewer = new NoticeViewer(7L, false);
		when(notices.recent(viewer, 3)).thenReturn(List.of());

		AppNoticeSummaryVO summary = service.summaryOf(viewer, 3);

		assertThat(summary.unread()).isZero();
		assertThat(summary.recent()).isEmpty();
	}

	private static SysNotice notice(Long id, String title, LocalDateTime release, LocalDateTime created,
									Boolean read) {
		SysNotice n = new SysNotice();
		n.setId(id);
		n.setTitle(title);
		n.setReleaseTime(release);
		n.setCreateTime(created);
		n.setReadFlag(read);
		return n;
	}
}
