package com.mugsun.boot.system;

import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysNoticeMapper;
import com.mugsun.boot.system.mapper.SysNoticeReadMapper;
import com.mugsun.boot.system.mapper.SysNoticeScopeMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.service.NoticeReadService;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按「谁在看」读公告：看的人只由参数决定。这里没有登录态也没有数据库，查询条件对不对看拼出来的 SQL。
 */
class NoticeReadServiceTest {

	private final SysNoticeMapper noticeMapper = mock(SysNoticeMapper.class);
	private final SysNoticeReadMapper readMapper = mock(SysNoticeReadMapper.class);
	private final SysNoticeScopeMapper scopeMapper = mock(SysNoticeScopeMapper.class);
	private final SysUserMapper userMapper = mock(SysUserMapper.class);

	private final NoticeReadService service =
		new NoticeReadService(noticeMapper, readMapper, scopeMapper, userMapper);

	@Test
	void aManagerFindsTitlesInAllNotices() {
		when(noticeMapper.selectListByQuery(any(QueryWrapper.class)))
			.thenReturn(List.of(notice(3L, "停电通知", 0), notice(2L, "停电预告", 1)));

		List<String> titles = service.titlesLike(new NoticeViewer(1L, true), "停电", 5);

		assertThat(titles).containsExactly("停电通知", "停电预告");
		assertThat(listQuery()).contains("%停电%").doesNotContain("all_visible");
		verifyNoInteractions(userMapper);
	}

	@Test
	void othersOnlyFindWhatIsOpenToAllOrNamedToThemOrTheirDept() {
		when(userMapper.selectOneById(7L)).thenReturn(user(7L, 40L));
		when(noticeMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

		service.titlesLike(new NoticeViewer(7L, false), "停电", 5);

		assertThat(listQuery()).contains("%停电%").contains("all_visible = 1")
			.contains("scope_type = 1 and scope_id = 7")
			.contains("scope_type = 2 and scope_id = 40");
	}

	@Test
	void aUserWithoutDeptMatchesNoDeptScope() {
		when(userMapper.selectOneById(7L)).thenReturn(user(7L, null));
		when(noticeMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of());

		service.titlesLike(new NoticeViewer(7L, false), "停电", 5);

		assertThat(listQuery()).contains("scope_type = 2 and scope_id = -1");
	}

	@Test
	void aBlankKeywordFindsNothingAndDoesNotQuery() {
		assertThat(service.titlesLike(new NoticeViewer(7L, false), " ", 5)).isEmpty();
		assertThat(service.titlesLike(new NoticeViewer(7L, false), null, 5)).isEmpty();
		verifyNoInteractions(noticeMapper, userMapper);
	}

	@Test
	void unreadCountLeavesOutWhatThisUserHasRead() {
		when(noticeMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(4L);

		assertThat(service.unreadCount(new NoticeViewer(9L, true))).isEqualTo(4L);

		ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(noticeMapper).selectCountByQuery(query.capture());
		assertThat(sql(query.getValue())).contains("sys_notice_read").contains("user_id = 9");
	}

	@Test
	void aNoticeOpenToAllIsVisibleWithoutLookingAtScopes() {
		when(noticeMapper.selectOneById(3L)).thenReturn(notice(3L, "停电通知", 1));

		assertThat(service.visible(new NoticeViewer(7L, false), 3L)).isTrue();
		verifyNoInteractions(scopeMapper, userMapper);
	}

	@Test
	void aScopedNoticeIsVisibleOnlyWhenAScopeNamesTheUserOrTheDept() {
		when(noticeMapper.selectOneById(3L)).thenReturn(notice(3L, "停电通知", 0));
		when(userMapper.selectOneById(7L)).thenReturn(user(7L, 40L));
		when(scopeMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L, 1L);

		assertThat(service.visible(new NoticeViewer(7L, false), 3L)).isFalse();
		assertThat(service.visible(new NoticeViewer(7L, false), 3L)).isTrue();
		assertThat(service.visible(new NoticeViewer(7L, true), 3L)).isTrue();
	}

	@Test
	void aMissingNoticeIsNotVisibleEvenToAManager() {
		NoticeViewer manager = new NoticeViewer(1L, true);

		assertThat(service.visible(manager, 404L)).isFalse();
		assertThat(service.visible(manager, null)).isFalse();
		assertThatThrownBy(() -> service.detail(manager, 404L))
			.isInstanceOf(ServiceException.class).hasMessage("通知不存在");
		assertThatThrownBy(() -> service.markRead(manager, 404L))
			.isInstanceOf(ServiceException.class).hasMessage("通知不存在");
	}

	private String listQuery() {
		ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(noticeMapper).selectListByQuery(query.capture());
		return sql(query.getValue());
	}

	private static String sql(QueryWrapper qw) {
		return qw.toSQL().replace("`", "").replace("\"", "");
	}

	private static SysNotice notice(long id, String title, int allVisible) {
		SysNotice n = new SysNotice();
		n.setId(id);
		n.setTitle(title);
		n.setAllVisible(allVisible);
		return n;
	}

	private static SysUser user(long id, Long deptId) {
		SysUser u = new SysUser();
		u.setId(id);
		u.setDeptId(deptId);
		return u;
	}
}
