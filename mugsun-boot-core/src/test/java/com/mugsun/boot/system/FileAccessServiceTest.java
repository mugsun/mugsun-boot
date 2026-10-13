package com.mugsun.boot.system;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.system.service.FileAccessService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B4 附件越权单测：租户过滤、归属/公开/manage 裁决、公开上传降级 */
class FileAccessServiceTest {

	private static final String TENANT = "100001";
	private static final long OWNER = 1L;
	private static final long OTHER = 2L;
	private static final Predicate<String> NONE = p -> false;

	private SysAttachMapper mapper;
	private FileAccessService service;

	@BeforeEach
	void setUp() {
		mapper = mock(SysAttachMapper.class);
		service = new FileAccessService(mapper);
	}

	private SysAttach attach(String access, String tenant) {
		SysAttach a = new SysAttach();
		a.setId(10L);
		a.setTenantId(tenant);
		a.setCreateUser(OWNER);
		a.setAccess(access);
		when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(a);
		return a;
	}

	private static Predicate<String> perms(String... codes) {
		return Set.of(codes)::contains;
	}

	@Test
	void othersPrivateFileDownloadIs404() {
		attach(OssConstants.ACCESS_PRIVATE, TENANT);
		assertThatThrownBy(() -> service.requireDownloadable(10L, TENANT, OTHER, NONE))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void ownerAndManageCanDownloadPrivate() {
		SysAttach a = attach(OssConstants.ACCESS_PRIVATE, TENANT);
		assertThat(service.requireDownloadable(10L, TENANT, OWNER, NONE)).isSameAs(a);
		assertThat(service.requireDownloadable(10L, TENANT, OTHER, perms(FileAccessService.PERM_MANAGE))).isSameAs(a);
	}

	@Test
	void othersPublicFileCanBeDownloaded() {
		SysAttach a = attach(OssConstants.ACCESS_PUBLIC, TENANT);
		assertThat(service.requireDownloadable(10L, TENANT, OTHER, NONE)).isSameAs(a);
	}

	@Test
	void othersPublicFileRemoveIsDenied() {
		attach(OssConstants.ACCESS_PUBLIC, TENANT);
		assertThat(service.findRemovable(10L, TENANT, OTHER, NONE)).isNull();
		assertThat(service.findRemovable(10L, TENANT, OTHER, perms(FileAccessService.PERM_REMOVE))).isNull();
	}

	@Test
	void ownerAndManageCanRemove() {
		SysAttach a = attach(OssConstants.ACCESS_PRIVATE, TENANT);
		assertThat(service.findRemovable(10L, TENANT, OWNER, NONE)).isSameAs(a);
		assertThat(service.findRemovable(10L, TENANT, OTHER, perms(FileAccessService.PERM_MANAGE))).isSameAs(a);
	}

	@Test
	void crossTenantIs404EvenForManageAndPublic() {
		attach(OssConstants.ACCESS_PUBLIC, "999999");
		Predicate<String> manage = perms(FileAccessService.PERM_MANAGE);
		assertThatThrownBy(() -> service.requireDownloadable(10L, TENANT, OWNER, manage))
			.isInstanceOf(ServiceException.class);
		assertThat(service.findRemovable(10L, TENANT, OWNER, manage)).isNull();
	}

	@Test
	void missingFileIs404() {
		when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(null);
		assertThatThrownBy(() -> service.requireDownloadable(10L, TENANT, OWNER, NONE))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void ordinaryUserPublicUploadDowngradedToPrivate() {
		assertThat(service.resolvePublic(OssConstants.ACCESS_PUBLIC, NONE)).isFalse();
		assertThat(service.resolvePublic(OssConstants.ACCESS_PUBLIC, perms(FileAccessService.PERM_PUBLIC))).isTrue();
		assertThat(service.resolvePublic(OssConstants.ACCESS_PRIVATE, perms(FileAccessService.PERM_PUBLIC))).isFalse();
	}

	@Test
	void legacyRowWithoutOwnerOnlyManage() {
		SysAttach a = attach(OssConstants.ACCESS_PRIVATE, TENANT);
		a.setCreateUser(null);
		assertThatThrownBy(() -> service.requireDownloadable(10L, TENANT, OWNER, NONE))
			.isInstanceOf(ServiceException.class);
		assertThat(service.findRemovable(10L, TENANT, OWNER, perms(FileAccessService.PERM_MANAGE))).isSameAs(a);
	}
}
