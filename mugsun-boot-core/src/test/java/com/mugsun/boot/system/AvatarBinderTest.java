package com.mugsun.boot.system;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.system.service.AvatarBinder;
import com.mugsun.boot.system.service.UserAvatarWriter;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 个人中心改头像：只能绑定本人上传、本租户、公开的图片；外链、别人的、私有的一律拒绝 */
class AvatarBinderTest {

	private static final long ME = 7L;
	private static final String T = "100001";

	private SysAttachMapper mapper;
	private UserAvatarWriter writer;
	private AvatarBinder binder;

	@BeforeEach
	void setUp() {
		mapper = mock(SysAttachMapper.class);
		writer = mock(UserAvatarWriter.class);
		binder = new AvatarBinder(mapper, writer);
	}

	private SysAttach stored(Long owner, String tenant, String access, String path) {
		SysAttach a = new SysAttach();
		a.setId(42L);
		a.setCreateUser(owner);
		a.setTenantId(tenant);
		a.setAccess(access);
		a.setPath(path);
		a.setUrl("http://127.0.0.1:8080/file/mugsun/" + path + "x.png");
		when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(a);
		return a;
	}

	@Test
	void bindRejectsExternalUrl() {
		when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(null);
		assertThatThrownBy(() -> binder.bind("https://evil.example/x.png", ME, T)).isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> binder.bind("javascript:alert(1)", ME, T)).isInstanceOf(ServiceException.class);
		verify(writer, never()).write(anyLong(), anyString());
	}

	@Test
	void bindRejectsOthersAttach() {
		stored(99L, T, OssConstants.ACCESS_PUBLIC, AvatarBinder.PATH_AVATAR);
		assertThatThrownBy(() -> binder.bind("/auth/avatar/raw/42", ME, T)).isInstanceOf(ServiceException.class);
		verify(writer, never()).write(anyLong(), anyString());
	}

	@Test
	void bindRejectsPrivateAttach() {
		stored(ME, T, OssConstants.ACCESS_PRIVATE, OssConstants.PATH_PRIVATE);
		assertThatThrownBy(() -> binder.bind("/auth/avatar/raw/42", ME, T)).isInstanceOf(ServiceException.class);
	}

	@Test
	void bindRejectsCrossTenant() {
		stored(ME, "999999", OssConstants.ACCESS_PUBLIC, AvatarBinder.PATH_AVATAR);
		assertThatThrownBy(() -> binder.bind("/auth/avatar/raw/42", ME, T)).isInstanceOf(ServiceException.class);
	}

	@Test
	void bindOwnPublicPasses() {
		stored(ME, T, OssConstants.ACCESS_PUBLIC, AvatarBinder.PATH_AVATAR);
		assertThat(binder.bind("/api/auth/avatar/raw/42", ME, T)).isEqualTo("/auth/avatar/raw/42");
		verify(writer).write(ME, "/auth/avatar/raw/42");
	}
}
