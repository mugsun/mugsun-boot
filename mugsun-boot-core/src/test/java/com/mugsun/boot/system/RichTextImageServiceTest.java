package com.mugsun.boot.system;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.system.service.AvatarUploadService;
import com.mugsun.boot.system.service.OssService;
import com.mugsun.boot.system.service.RichTextImageService;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 正文图片：只收位图、限 5MB、存公开区 public/richtext/；匿名读取只认本区且文件名对得上的图片 */
class RichTextImageServiceTest {

	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
	private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
	private static final String STORED = "0123456789abcdef0123456789abcdef.png";

	private FileStorageService storage;
	private UploadPretreatment pre;
	private SysAttachMapper mapper;
	private RichTextImageService service;

	@BeforeEach
	void setUp() {
		storage = mock(FileStorageService.class);
		pre = mock(UploadPretreatment.class);
		OssService oss = mock(OssService.class);
		when(oss.activePlatform()).thenReturn("local-1");
		when(storage.of(any(MultipartFile.class))).thenReturn(pre);
		when(pre.setPlatform(anyString())).thenReturn(pre);
		when(pre.setPath(anyString())).thenReturn(pre);
		when(pre.setSaveFilename(anyString())).thenReturn(pre);
		when(pre.setContentType(anyString())).thenReturn(pre);
		when(pre.putAttr(anyString(), any())).thenReturn(pre);
		FileInfo info = new FileInfo();
		info.setId("42");
		info.setFilename(STORED);
		info.setPath(RichTextImageService.PATH_RICHTEXT);
		info.setUrl("http://127.0.0.1:8080/file/mugsun/public/richtext/" + STORED);
		when(pre.upload()).thenReturn(info);
		mapper = mock(SysAttachMapper.class);
		service = new RichTextImageService(storage, oss, mapper);
	}

	@Test
	void uploadStoresPublicImageAndReturnsReadAddress() {
		SysAttach a = service.upload(new MockMultipartFile("file", "示意图.PNG", "image/png", PNG));
		assertThat(a.getId()).isEqualTo(42L);
		assertThat(a.getAccess()).isEqualTo(OssConstants.ACCESS_PUBLIC);
		// 返回的是本系统的读取地址，不是存储平台的地址
		assertThat(a.getUrl()).isEqualTo("/system/richtext/image/raw/42/" + STORED);
		verify(pre).setPath(RichTextImageService.PATH_RICHTEXT);
		verify(pre).setSaveFilename(matches("[0-9a-f]{32}\\.png"));
		verify(pre).setContentType("image/png");
		verify(pre).putAttr(OssConstants.ATTR_ACCESS, OssConstants.ACCESS_PUBLIC);
	}

	@Test
	void contentTypeComesFromExtensionNotFromClient() {
		// 粘贴、拖拽时浏览器报的类型不一定准：类型由扩展名和文件头决定
		service.upload(new MockMultipartFile("file", "photo.jpeg", "application/octet-stream", JPG));
		verify(pre).setSaveFilename(matches("[0-9a-f]{32}\\.jpeg"));
		verify(pre).setContentType("image/jpeg");
	}

	@Test
	void nonImageRejected() {
		List<MockMultipartFile> files = List.of(
			new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)),
			new MockMultipartFile("file", "a.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)),
			new MockMultipartFile("file", "a.bmp", "image/bmp", new byte[]{'B', 'M', 0, 0}),
			new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p'}),
			new MockMultipartFile("file", "noext", "image/png", PNG));
		for (MockMultipartFile f : files) {
			assertThatThrownBy(() -> service.upload(f)).as(f.getOriginalFilename())
				.isInstanceOf(ServiceException.class).hasMessageContaining("png");
		}
		verify(storage, never()).of(any(MultipartFile.class));
	}

	@Test
	void renamedFileRejected() {
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png",
			"<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.gif", "image/gif", PNG)))
			.isInstanceOf(ServiceException.class);
		verify(storage, never()).of(any(MultipartFile.class));
	}

	@Test
	void sizeLimitIsFiveMegabytes() {
		byte[] atLimit = new byte[(int) RichTextImageService.MAX_SIZE];
		System.arraycopy(PNG, 0, atLimit, 0, PNG.length);
		assertThat(service.upload(new MockMultipartFile("file", "ok.png", "image/png", atLimit)).getId()).isEqualTo(42L);

		byte[] over = new byte[(int) RichTextImageService.MAX_SIZE + 1];
		System.arraycopy(PNG, 0, over, 0, PNG.length);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "big.png", "image/png", over)))
			.isInstanceOf(ServiceException.class).hasMessageContaining("5MB");
	}

	@Test
	void emptyRejected() {
		assertThatThrownBy(() -> service.upload(null)).isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png", new byte[0])))
			.isInstanceOf(ServiceException.class);
		verify(storage, never()).of(any(MultipartFile.class));
	}

	@Test
	void storageFailureDoesNotLeakDetails() {
		when(pre.upload()).thenThrow(new IllegalStateException("disk /data full"));
		assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png", PNG)))
			.isInstanceOf(ServiceException.class).hasMessageNotContaining("/data");
	}

	private void stored(String access, String path, String filename) {
		SysAttach a = new SysAttach();
		a.setId(42L);
		a.setAccess(access);
		a.setPath(path);
		a.setFilename(filename);
		when(mapper.selectOneById(any())).thenReturn(a);
	}

	@Test
	void rawNeedsTheStoredFilename() {
		stored(OssConstants.ACCESS_PUBLIC, RichTextImageService.PATH_RICHTEXT, STORED);
		assertThat(service.requireServable(42L, STORED).getId()).isEqualTo(42L);
		// 编号是递增的，只知道编号读不到：文件名必须对得上
		assertThatThrownBy(() -> service.requireServable(42L, "ffffffffffffffffffffffffffffffff.png"))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.requireServable(42L, null)).isInstanceOf(ServiceException.class);
	}

	@Test
	void rawRefusesOtherAreasAndPrivateFiles() {
		stored(OssConstants.ACCESS_PUBLIC, OssConstants.PATH_PUBLIC, STORED);
		assertThatThrownBy(() -> service.requireServable(42L, STORED)).isInstanceOf(ServiceException.class);
		stored(OssConstants.ACCESS_PUBLIC, AvatarUploadService.PATH_AVATAR, STORED);
		assertThatThrownBy(() -> service.requireServable(42L, STORED)).isInstanceOf(ServiceException.class);
		stored(OssConstants.ACCESS_PRIVATE, RichTextImageService.PATH_RICHTEXT, STORED);
		assertThatThrownBy(() -> service.requireServable(42L, STORED)).isInstanceOf(ServiceException.class);
		stored(OssConstants.ACCESS_PUBLIC, RichTextImageService.PATH_RICHTEXT + "../", STORED);
		assertThatThrownBy(() -> service.requireServable(42L, STORED)).isInstanceOf(ServiceException.class);
	}

	@Test
	void rawRefusesNonImageEvenInsideTheArea() {
		String html = "0123456789abcdef0123456789abcdef.html";
		stored(OssConstants.ACCESS_PUBLIC, RichTextImageService.PATH_RICHTEXT, html);
		assertThatThrownBy(() -> service.requireServable(42L, html)).isInstanceOf(ServiceException.class);
	}

	@Test
	void rawRefusesUnknownId() {
		when(mapper.selectOneById(any())).thenReturn(null);
		assertThatThrownBy(() -> service.requireServable(42L, STORED)).isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.requireServable(null, STORED)).isInstanceOf(ServiceException.class);
	}

	@Test
	void contentTypeOnReadComesFromStoredName() {
		SysAttach a = new SysAttach();
		a.setFilename("0123456789abcdef0123456789abcdef.webp");
		a.setContentType("text/html");
		assertThat(RichTextImageService.contentTypeOf(a)).isEqualTo("image/webp");
	}
}
