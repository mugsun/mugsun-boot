package com.mugsun.boot.system;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.system.service.AvatarUploadService;
import com.mugsun.boot.system.service.UserAvatarWriter;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.system.service.OssService;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** B4 头像专用上传：普通用户可传且为公开；非图片/伪造/超大拒绝 */
class AvatarUploadServiceTest {

	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

	private FileStorageService storage;
	private UploadPretreatment pre;
	private AvatarUploadService service;
	private SysAttachMapper mapper;
	private UserAvatarWriter writer;
	private static final long ME = 7L;
	private static final String T = "100001";

	@BeforeEach
	void setUp() {
		storage = mock(FileStorageService.class);
		pre = mock(UploadPretreatment.class);
		OssService oss = mock(OssService.class);
		when(oss.activePlatform()).thenReturn("local-1");
		when(storage.of(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn(pre);
		when(pre.setPlatform(anyString())).thenReturn(pre);
		when(pre.setPath(anyString())).thenReturn(pre);
		when(pre.putAttr(anyString(), any())).thenReturn(pre);
		FileInfo info = new FileInfo();
		info.setId("42");
		info.setUrl("/file/public/avatar/x.png");
		when(pre.upload()).thenReturn(info);
		mapper = mock(SysAttachMapper.class);
		writer = mock(UserAvatarWriter.class);
		service = new AvatarUploadService(storage, oss, mapper, writer);
	}

	@Test
	void ordinaryUserUploadsAvatarAsPublic() {
		SysAttach a = service.upload(new MockMultipartFile("file", "me.png", "image/png", PNG), ME);
		assertThat(a.getAccess()).isEqualTo(OssConstants.ACCESS_PUBLIC);
		assertThat(a.getUrl()).isEqualTo("/auth/avatar/raw/42");
		verify(writer).write(ME, "/auth/avatar/raw/42");
		assertThat(a.getId()).isEqualTo(42L);
		verify(pre).setPath(AvatarUploadService.PATH_AVATAR);
		verify(pre).putAttr(OssConstants.ATTR_ACCESS, OssConstants.ACCESS_PUBLIC);
	}

	@Test
	void nonImageRejected() {
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.pdf", "application/pdf", PNG), ME))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.svg", "image/svg+xml", "<svg/>".getBytes()), ME))
			.isInstanceOf(ServiceException.class);
		verify(storage, never()).of(any(org.springframework.web.multipart.MultipartFile.class));
	}

	@Test
	void spoofedImageRejected() {
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png", "<html>".getBytes()), ME))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "text/html", PNG), ME))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void oversizeRejected() {
		byte[] big = new byte[(int) AvatarUploadService.MAX_SIZE + 1];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "big.png", "image/png", big), ME))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void emptyRejected() {
		assertThatThrownBy(() -> service.upload(null, ME)).isInstanceOf(ServiceException.class);
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
		when(mapper.selectOneById(any())).thenReturn(a);
		return a;
	}

	@Test
	void bindRejectsExternalUrl() {
		when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(null);
		assertThatThrownBy(() -> service.bind("https://evil.example/x.png", ME, T)).isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.bind("javascript:alert(1)", ME, T)).isInstanceOf(ServiceException.class);
		verify(writer, never()).write(anyLong(), anyString());
	}

	@Test
	void bindRejectsOthersAttach() {
		stored(99L, T, OssConstants.ACCESS_PUBLIC, AvatarUploadService.PATH_AVATAR);
		assertThatThrownBy(() -> service.bind("/auth/avatar/raw/42", ME, T)).isInstanceOf(ServiceException.class);
		verify(writer, never()).write(anyLong(), anyString());
	}

	@Test
	void bindRejectsPrivateAttach() {
		stored(ME, T, OssConstants.ACCESS_PRIVATE, OssConstants.PATH_PRIVATE);
		assertThatThrownBy(() -> service.bind("/auth/avatar/raw/42", ME, T)).isInstanceOf(ServiceException.class);
	}

	@Test
	void bindRejectsCrossTenant() {
		stored(ME, "999999", OssConstants.ACCESS_PUBLIC, AvatarUploadService.PATH_AVATAR);
		assertThatThrownBy(() -> service.bind("/auth/avatar/raw/42", ME, T)).isInstanceOf(ServiceException.class);
	}

	@Test
	void bindOwnPublicPasses() {
		stored(ME, T, OssConstants.ACCESS_PUBLIC, AvatarUploadService.PATH_AVATAR);
		assertThat(service.bind("/api/auth/avatar/raw/42", ME, T)).isEqualTo("/auth/avatar/raw/42");
		verify(writer).write(ME, "/auth/avatar/raw/42");
	}

	@Test
	void rawOnlyServesAvatarArea() {
		stored(ME, T, OssConstants.ACCESS_PUBLIC, AvatarUploadService.PATH_AVATAR);
		assertThat(service.requireServable(42L).getId()).isEqualTo(42L);
		stored(ME, T, OssConstants.ACCESS_PUBLIC, OssConstants.PATH_PUBLIC);
		assertThatThrownBy(() -> service.requireServable(42L)).isInstanceOf(ServiceException.class);
		stored(ME, T, OssConstants.ACCESS_PRIVATE, AvatarUploadService.PATH_AVATAR);
		assertThatThrownBy(() -> service.requireServable(42L)).isInstanceOf(ServiceException.class);
	}
}
