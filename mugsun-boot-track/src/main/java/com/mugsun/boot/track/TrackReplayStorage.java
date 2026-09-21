package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 回放块对象存储通道（x-file-storage，私有桶语义照附件域 G41/G89 先例）：
 * 本体绝不进数据库事实表，gzip 字节原样落默认存储平台。
 * <p><b>对象键</b>：{@code replay/{app_key}/{yyyyMM}/{session_id}/{seq}.gz}——yyyyMM 取首块到达月（UTC），
 * 同会话所有块同目录；track_replay.storage_key 只存首块完整键（含平台 basePath 前缀），
 * 任意块键 = {@code dir(storage_key) + seq + ".gz"} 纯推导（约定 seq 自 0 连续递增，个别被拒/丢失的
 * seq 读取返回不存在由前端跳过）。
 * <p><b>登记纪律</b>：上传 attr 带 {@link OssConstants#ATTR_INTERNAL_OBJECT} 内部对象标记，
 * AttachFileRecorder 据此跳过 sys_attach 登记（回放块非附件，元数据自管于 track_replay）。
 * <p><b>平台坐标</b>：写入记录 platform + basePath 到元数据行，读取/删除按原坐标重建 FileInfo——
 * 默认平台配置日后切换不影响存量回放寻址。
 */
@Component
public class TrackReplayStorage {

	private static final Logger log = LoggerFactory.getLogger(TrackReplayStorage.class);

	/** 对象键月份段格式（UTC） */
	private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.REPLAY_PATH_MONTH_PATTERN);

	private final FileStorageService fileStorageService;

	public TrackReplayStorage(FileStorageService fileStorageService) {
		this.fileStorageService = fileStorageService;
	}

	/** 块对象路径（x-file-storage path 段，不含 basePath；尾带 /） */
	public static String blockPath(String appKey, long receivedAtMs, String sessionId) {
		String month = MONTH_FORMAT.format(Instant.ofEpochMilli(receivedAtMs).atOffset(ZoneOffset.UTC));
		return TrackConstants.REPLAY_PATH_PREFIX + appKey + "/" + month + "/" + sessionId + "/";
	}

	/** 块文件名（{seq}.gz） */
	public static String blockFilename(int seq) {
		return seq + TrackConstants.REPLAY_BLOCK_SUFFIX;
	}

	/** 完整对象键（含 basePath 前缀）：{@code basePath + path + filename}，与 FileInfo 落库口径一致 */
	public static String fullKey(String basePath, String path, int seq) {
		return (basePath == null ? "" : basePath) + path + blockFilename(seq);
	}

	/** 由首块键推导任意块完整对象键（同会话同目录，仅替换文件名） */
	public static String deriveKey(String firstBlockKey, int seq) {
		return firstBlockKey.substring(0, firstBlockKey.lastIndexOf('/') + 1) + blockFilename(seq);
	}

	/**
	 * 写入块（默认存储平台）：返回框架回执 FileInfo（platform/basePath/path/filename 均已填充）。
	 * 键确定（同 session+seq 重写为同键覆盖），消费重试天然幂等。
	 * 注意 x-file-storage 的 saveFilename 不自动拼扩展名（照 FileController 直传先例手工带后缀）。
	 */
	public FileInfo save(TrackReplayBlock block) {
		String filename = blockFilename(block.getSeq());
		return fileStorageService.of(block.getGzBytes())
			.setPlatform(fileStorageService.getDefaultPlatform())
			.setPath(blockPath(block.getAppKey(), block.getReceivedAtMs(), block.getSessionId()))
			.setOriginalFilename(filename)
			.setSaveFilename(filename)
			.setContentType(TrackConstants.REPLAY_BLOCK_CONTENT_TYPE)
			.putAttr(OssConstants.ATTR_INTERNAL_OBJECT, OssConstants.INTERNAL_OBJECT_TRACK_REPLAY)
			.upload();
	}

	/** 读取块字节（按元数据行记录的平台坐标重建 FileInfo；对象不存在/平台下线抛异常由调用方转 404） */
	public byte[] load(String platform, String basePath, String firstBlockKey, int seq) {
		return fileStorageService.download(toFileInfo(platform, basePath, firstBlockKey, seq)).bytes();
	}

	/** 删除会话全部块（seq ∈ [0, lastSeq]）；返回删除失败数（失败对象留待下轮/人工，元数据不受阻） */
	public int deleteAll(String platform, String basePath, String firstBlockKey, int lastSeq) {
		int failed = 0;
		for (int seq = 0; seq <= lastSeq; seq++) {
			try {
				if (!fileStorageService.delete(toFileInfo(platform, basePath, firstBlockKey, seq))) {
					failed++;
					log.warn("回放块删除返回 false：{}", deriveKey(firstBlockKey, seq));
				}
			} catch (Exception e) {
				failed++;
				log.warn("回放块删除异常 {}：{}", deriveKey(firstBlockKey, seq), e.getMessage());
			}
		}
		return failed;
	}

	/** 由存储坐标 + 首块键重建任意块 FileInfo（目录取首块键目录段，文件名按 seq 推导）。
	 *  url 一并回填（对象键口径）：delete 链路按 url 级联销登记，recorder 据此识别内部对象静默跳过 */
	private FileInfo toFileInfo(String platform, String basePath, String firstBlockKey, int seq) {
		String bp = basePath == null ? "" : basePath;
		// path 段 = 完整键去掉 basePath 前缀与文件名（FileInfo 三段坐标与平台 save 时口径一致）
		String path = firstBlockKey.substring(bp.length(), firstBlockKey.lastIndexOf('/') + 1);
		FileInfo info = new FileInfo();
		info.setPlatform(platform);
		info.setBasePath(bp);
		info.setPath(path);
		info.setFilename(blockFilename(seq));
		info.setUrl(bp + path + blockFilename(seq));
		return info;
	}
}
