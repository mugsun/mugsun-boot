package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.springframework.stereotype.Component;

/**
 * sourcemap 对象存储通道（x-file-storage，私有存储语义照回放域 G100 先例）：
 * .map 本体原样落默认存储平台，绝不进数据库事实表。
 * <p><b>对象键</b>：{@code sourcemap/{app_key}/{release}/{filename}}——三段时间确定，
 * 同 (app_key, release, filename) 重传写为同键覆盖，天然幂等；track_sourcemap.storage_key
 * 存完整键（含平台 basePath 前缀）。
 * <p><b>登记纪律</b>：上传 attr 带 {@link OssConstants#ATTR_INTERNAL_OBJECT} 内部对象标记，
 * AttachFileRecorder 据此跳过 sys_attach 登记（sourcemap 非附件，元数据自管于 track_sourcemap）。
 * <p><b>平台坐标</b>：写入记录 platform + basePath 到元数据行，读取/删除按原坐标重建 FileInfo——
 * 默认平台配置日后切换不影响存量文件寻址。
 */
@Component
public class TrackSourcemapStorage {

	private final FileStorageService fileStorageService;

	public TrackSourcemapStorage(FileStorageService fileStorageService) {
		this.fileStorageService = fileStorageService;
	}

	/** 对象路径（x-file-storage path 段，不含 basePath；尾带 /） */
	public static String pathOf(String appKey, String release) {
		return TrackConstants.SOURCEMAP_PATH_PREFIX + appKey + "/" + release + "/";
	}

	/** 完整对象键（含 basePath 前缀）：{@code basePath + path + filename}，与 FileInfo 落库口径一致 */
	public static String fullKey(String basePath, String path, String filename) {
		return (basePath == null ? "" : basePath) + path + filename;
	}

	/**
	 * 写入 .map 文件（默认存储平台）：返回框架回执 FileInfo（platform/basePath/path/filename 均已填充）。
	 * 键确定（同 app_key+release+filename 重写为同键覆盖）。
	 * 注意 x-file-storage 的 saveFilename 不自动拼扩展名（照回放域先例手工带完整文件名）。
	 */
	public FileInfo save(String appKey, String release, String filename, byte[] bytes) {
		return fileStorageService.of(bytes)
			.setPlatform(fileStorageService.getDefaultPlatform())
			.setPath(pathOf(appKey, release))
			.setOriginalFilename(filename)
			.setSaveFilename(filename)
			.setContentType(TrackConstants.SOURCEMAP_CONTENT_TYPE)
			.putAttr(OssConstants.ATTR_INTERNAL_OBJECT, OssConstants.INTERNAL_OBJECT_TRACK_SOURCEMAP)
			.upload();
	}

	/** 读取文件字节（按元数据行记录的平台坐标重建 FileInfo；对象不存在/平台下线抛异常由调用方转 400） */
	public byte[] load(String platform, String basePath, String storageKey) {
		return fileStorageService.download(toFileInfo(platform, basePath, storageKey)).bytes();
	}

	/** 删除文件（按元数据行记录的平台坐标重建 FileInfo） */
	public boolean delete(String platform, String basePath, String storageKey) {
		return fileStorageService.delete(toFileInfo(platform, basePath, storageKey));
	}

	/** 由存储坐标 + 完整对象键重建 FileInfo（path/filename 段由键纯推导）。
	 *  url 一并回填（对象键口径）：delete 链路按 url 级联销登记，recorder 据此识别内部对象静默跳过 */
	private FileInfo toFileInfo(String platform, String basePath, String storageKey) {
		String bp = basePath == null ? "" : basePath;
		String path = storageKey.substring(bp.length(), storageKey.lastIndexOf('/') + 1);
		String filename = storageKey.substring(storageKey.lastIndexOf('/') + 1);
		FileInfo info = new FileInfo();
		info.setPlatform(platform);
		info.setBasePath(bp);
		info.setPath(path);
		info.setFilename(filename);
		info.setUrl(storageKey);
		return info;
	}
}
