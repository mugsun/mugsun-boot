package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.spring.SpringFileStorageProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 接口响应体对象存储通道（x-file-storage，私有存储语义照回放域 G100/sourcemap 域 G101 先例）：
 * body 本体绝不进数据库事实表，gzip 字节原样落默认存储平台。
 * <p><b>对象键</b>：{@code api-body/{app_key}/{yyyyMM}/{event_id}.json.gz}——yyyyMM 取上传到达月（UTC）。
 * <b>无元数据表</b>（本域与回放/sourcemap 的关键差异）：读取/清理按 track_event.props->>'body_ref'
 * （= api_request 事件自身 event_id）+ 事件 received_at 纯推导对象键，不登记任何坐标行。
 * <p><b>平台坐标</b>：写入恒为默认存储平台（{@code fileStorageService.getDefaultPlatform()}，同回放/sourcemap 域）；
 * 读取/删除按同约定取当前默认平台，basePath 由 yaml 配置（{@link SpringFileStorageProperties}）按平台名还原。
 * 默认平台或其 basePath 变更后，变更前写入的存量对象在短保留期（≤{@value TrackConstants#API_BODY_MAX_RETENTION_DAYS} 天）
 * 内按新坐标寻址失败：读取按「body 未采集或已清理」诚实兜底，残留对象随事件清单到期自然孤儿化
 * （短周期小体量，可接受；日后如需跨平台变更完整寻址，补坐标登记表同 track_sourcemap 范式）。
 * <p><b>登记纪律</b>：上传 attr 带 {@link OssConstants#ATTR_INTERNAL_OBJECT} 内部对象标记，
 * AttachFileRecorder 据此跳过 sys_attach 登记（响应体非附件，元数据零登记）。
 */
@Component
public class TrackApiBodyStorage {

	/** 对象键月份段格式（UTC） */
	private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.API_BODY_PATH_MONTH_PATTERN);

	private final FileStorageService fileStorageService;
	private final SpringFileStorageProperties properties;

	public TrackApiBodyStorage(FileStorageService fileStorageService, SpringFileStorageProperties properties) {
		this.fileStorageService = fileStorageService;
		this.properties = properties;
	}

	/** 对象路径（x-file-storage path 段，不含 basePath；尾带 /）：api-body/{app_key}/{yyyyMM}/ */
	public static String pathOf(String appKey, long receivedAtMs) {
		String month = MONTH_FORMAT.format(Instant.ofEpochMilli(receivedAtMs).atOffset(ZoneOffset.UTC));
		return TrackConstants.API_BODY_PATH_PREFIX + appKey + "/" + month + "/";
	}

	/** 对象文件名（{event_id}.json.gz；body_ref = 事件自身 event_id） */
	public static String filenameOf(String bodyRef) {
		return bodyRef + TrackConstants.API_BODY_FILE_SUFFIX;
	}

	/** 相对对象键（不含平台 basePath）：读取/删除/清理的键推导唯一出口 */
	public static String relativeKey(String appKey, long receivedAtMs, String bodyRef) {
		return pathOf(appKey, receivedAtMs) + filenameOf(bodyRef);
	}

	/**
	 * 写入响应体（默认存储平台）：键确定（同 event_id 重写为同键覆盖，幂等重发天然安全）。
	 * 落储字节恒为 gzip（明文体已由服务端补压）。返回框架回执 FileInfo。
	 */
	public FileInfo save(String appKey, String eventId, long receivedAtMs, byte[] gzBytes) {
		String filename = filenameOf(eventId);
		return fileStorageService.of(gzBytes)
			.setPlatform(fileStorageService.getDefaultPlatform())
			.setPath(pathOf(appKey, receivedAtMs))
			.setOriginalFilename(filename)
			.setSaveFilename(filename)
			.setContentType(TrackConstants.API_BODY_CONTENT_TYPE)
			.putAttr(OssConstants.ATTR_INTERNAL_OBJECT, OssConstants.INTERNAL_OBJECT_TRACK_API_BODY)
			.upload();
	}

	/** 读取响应体 gzip 字节（按默认平台坐标重建 FileInfo；对象不存在/平台下线抛异常由调用方转 400） */
	public byte[] load(String appKey, long receivedAtMs, String bodyRef) {
		return fileStorageService.download(toFileInfo(relativeKey(appKey, receivedAtMs, bodyRef))).bytes();
	}

	/** 删除响应体对象（按默认平台坐标重建 FileInfo；清理任务用，返回删除成败） */
	public boolean delete(String appKey, long receivedAtMs, String bodyRef) {
		return fileStorageService.delete(toFileInfo(relativeKey(appKey, receivedAtMs, bodyRef)));
	}

	/** 由相对对象键重建 FileInfo（平台=当前默认平台，basePath 由 yaml 配置按平台名还原）。
	 *  url 一并回填（完整对象键口径）：delete 链路按 url 级联销登记，recorder 据此识别内部对象静默跳过 */
	private FileInfo toFileInfo(String relativeKey) {
		String basePath = defaultBasePath();
		FileInfo info = new FileInfo();
		info.setPlatform(fileStorageService.getDefaultPlatform());
		info.setBasePath(basePath);
		info.setPath(relativeKey.substring(0, relativeKey.lastIndexOf('/') + 1));
		info.setFilename(relativeKey.substring(relativeKey.lastIndexOf('/') + 1));
		info.setUrl(basePath + relativeKey);
		return info;
	}

	/** 当前默认平台的 basePath（yaml 配置还原；本工程默认平台恒为 yaml local-plus，DB 注册平台从不作采集落点） */
	private String defaultBasePath() {
		String platform = fileStorageService.getDefaultPlatform();
		return properties.getLocalPlus().stream()
			.filter(config -> platform.equals(config.getPlatform()))
			.findFirst()
			.map(config -> config.getBasePath() == null ? "" : config.getBasePath())
			.orElse("");
	}
}
