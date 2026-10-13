package com.mugsun.boot.system;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.service.AvatarUploadService;
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
import static org.mockito.Mockito.*;

/** B4 头像专用上传：普通用户可传且为公开；非图片/伪造/超大拒绝 */
class AvatarUploadServiceTest {

	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

	private FileStorageService storage;
	private UploadPretreatment pre;
	private AvatarUploadService service;

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
		service = new AvatarUploadService(storage, oss);
	}

	@Test
	void ordinaryUserUploadsAvatarAsPublic() {
		SysAttach a = service.upload(new MockMultipartFile("file", "me.png", "image/png", PNG));
		assertThat(a.getAccess()).isEqualTo(OssConstants.ACCESS_PUBLIC);
		assertThat(a.getUrl()).isEqualTo("/file/public/avatar/x.png");
		assertThat(a.getId()).isEqualTo(42L);
		verify(pre).setPath(AvatarUploadService.PATH_AVATAR);
		verify(pre).putAttr(OssConstants.ATTR_ACCESS, OssConstants.ACCESS_PUBLIC);
	}

	@Test
	void nonImageRejected() {
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.pdf", "application/pdf", PNG)))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.svg", "image/svg+xml", "<svg/>".getBytes())))
			.isInstanceOf(ServiceException.class);
		verify(storage, never()).of(any(org.springframework.web.multipart.MultipartFile.class));
	}

	@Test
	void spoofedImageRejected() {
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png", "<html>".getBytes())))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "text/html", PNG)))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void oversizeRejected() {
		byte[] big = new byte[(int) AvatarUploadService.MAX_SIZE + 1];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "big.png", "image/png", big)))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void emptyRejected() {
		assertThatThrownBy(() -> service.upload(null)).isInstanceOf(ServiceException.class);
	}
}
