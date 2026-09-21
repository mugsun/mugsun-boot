package com.mugsun.boot.system.service;

import cn.hutool.core.lang.Dict;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.entity.SysAttachPart;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.system.mapper.SysAttachPartMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.dromara.x.file.storage.core.upload.FilePartInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 附件登记桥：x-file-storage 上传/删除生命周期统一落 sys_attach（上传即登记、物删级联销登记），
 * 分片上传会话的分片落 sys_attach_part。
 * <p>url 语义：平台 save 后的 FileInfo.url（domain + basePath + path + filename）为登记唯一键；
 * 访问级别等业务扩展字段经 FileInfo attr（{@link OssConstants#ATTR_ACCESS}）传递。
 */
@Component
public class AttachFileRecorder implements FileRecorder {

	private static final Logger log = LoggerFactory.getLogger(AttachFileRecorder.class);

	private final SysAttachMapper attachMapper;
	private final SysAttachPartMapper partMapper;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AttachFileRecorder(SysAttachMapper attachMapper, SysAttachPartMapper partMapper) {
		this.attachMapper = attachMapper;
		this.partMapper = partMapper;
	}

	/** 上传完成登记附件；登记行主键回写 {@link FileInfo#setId}，供调用方直接回执（免回表）。
	 *  内部对象（attr 带 {@link OssConstants#ATTR_INTERNAL_OBJECT}，如埋点回放块）非附件，跳过登记 */
	@Override
	public boolean save(FileInfo info) {
		if (isInternalObject(info)) {
			return true;
		}
		SysAttach attach = new SysAttach();
		attach.setName(info.getOriginalFilename());
		attach.setUrl(info.getUrl());
		attach.setPath(info.getPath());
		attach.setFilename(info.getFilename());
		attach.setExt(info.getExt());
		attach.setContentType(info.getContentType());
		attach.setSize(info.getSize());
		attach.setPlatform(info.getPlatform());
		attach.setBasePath(info.getBasePath());
		attach.setAccess(accessOf(info));
		attachMapper.insertSelective(attach);
		info.setId(String.valueOf(attach.getId()));
		return true;
	}

	/** 按 url 更新登记（缩略图等元数据变更场景） */
	@Override
	public void update(FileInfo info) {
		SysAttach attach = selectByUrl(info.getUrl());
		if (attach == null) {
			return;
		}
		if (info.getSize() != null) {
			attach.setSize(info.getSize());
		}
		if (info.getFilename() != null) {
			attach.setFilename(info.getFilename());
		}
		if (info.getContentType() != null) {
			attach.setContentType(info.getContentType());
		}
		attachMapper.update(attach);
	}

	/** 按 url 取登记并还原 FileInfo（url 为空直接未命中，不制造全表风险） */
	@Override
	public FileInfo getByUrl(String url) {
		SysAttach attach = selectByUrl(url);
		if (attach == null) {
			return null;
		}
		FileInfo info = new FileInfo();
		info.setId(String.valueOf(attach.getId()));
		info.setUrl(attach.getUrl());
		info.setSize(attach.getSize());
		info.setFilename(attach.getFilename());
		info.setOriginalFilename(attach.getName());
		info.setBasePath(attach.getBasePath());
		info.setPath(attach.getPath());
		info.setExt(attach.getExt());
		info.setContentType(attach.getContentType());
		info.setPlatform(attach.getPlatform());
		info.setAttr(Dict.create().set(OssConstants.ATTR_ACCESS, attach.getAccess()));
		return info;
	}

	/** 物理删除的级联销登记：按 url 逻辑删除登记行（幂等，重复删返回未命中）。
	 *  回放块/sourcemap/接口响应体等内部对象从未登记（按 url 路径段识别），静默跳过不打告警 */
	@Override
	public boolean delete(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		// 埋点内部对象路径段（与 TrackConstants 对齐字面量，避免核心依赖 track 模块常量）
		if (url.contains("/replay/")
			|| url.contains("/sourcemap/")
			|| url.contains("/api-body/")) {
			return true;
		}
		int rows = attachMapper.deleteByQuery(QueryWrapper.create().eq("url", url));
		if (rows == 0) {
			log.warn("附件登记销户未命中 url={}（物理对象已删，登记行不存在或已删）", url);
		}
		return rows > 0;
	}

	@Override
	public void saveFilePart(FilePartInfo partInfo) {
		SysAttachPart part = new SysAttachPart();
		part.setPlatform(partInfo.getPlatform());
		part.setUploadId(partInfo.getUploadId());
		part.setETag(partInfo.getETag());
		part.setPartNumber(partInfo.getPartNumber());
		part.setPartSize(partInfo.getPartSize());
		part.setHashInfo(hashJson(partInfo));
		part.setLastModified(toLocalDateTime(partInfo.getLastModified()));
		partMapper.insertSelective(part);
	}

	@Override
	public void deleteFilePartByUploadId(String uploadId) {
		if (uploadId == null || uploadId.isBlank()) {
			return;
		}
		partMapper.deleteByQuery(QueryWrapper.create().eq("upload_id", uploadId));
	}

	private SysAttach selectByUrl(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		return attachMapper.selectOneByQuery(QueryWrapper.create().eq("url", url).orderBy("id", false));
	}

	/** 内部对象判定：attr 携带 {@link OssConstants#ATTR_INTERNAL_OBJECT} 标记（回放块等非附件对象，不落 sys_attach） */
	private boolean isInternalObject(FileInfo info) {
		Dict attr = info.getAttr();
		return attr != null && attr.getStr(OssConstants.ATTR_INTERNAL_OBJECT) != null;
	}

	/** 访问级别：attr 未携带（如框架内部流转）按私有兜底，宁可收紧不可外泄 */
	private String accessOf(FileInfo info) {
		Dict attr = info.getAttr();
		if (attr == null) {
			return OssConstants.ACCESS_PRIVATE;
		}
		String access = attr.getStr(OssConstants.ATTR_ACCESS);
		return OssConstants.ACCESS_PUBLIC.equals(access) ? OssConstants.ACCESS_PUBLIC : OssConstants.ACCESS_PRIVATE;
	}

	private String hashJson(FilePartInfo partInfo) {
		if (partInfo.getHashInfo() == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(partInfo.getHashInfo());
		} catch (Exception e) {
			log.warn("分片摘要序列化失败 uploadId={}: {}", partInfo.getUploadId(), e.getMessage());
			return null;
		}
	}

	private LocalDateTime toLocalDateTime(Date date) {
		return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
	}
}
