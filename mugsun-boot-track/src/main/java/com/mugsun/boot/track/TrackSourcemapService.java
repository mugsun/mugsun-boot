package com.mugsun.boot.track;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackApp;
import com.mugsun.boot.track.entity.TrackSourcemap;
import com.mugsun.boot.track.mapper.TrackAppMapper;
import com.mugsun.boot.track.mapper.TrackSourcemapMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.dromara.x.file.storage.core.FileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sourcemap 管理服务（track_sourcemap 读写唯一出口，G101）：类级 {@link TrackDS} 路由埋点独立库，
 * Flex 租户行级插件对本表自动拼 tenant_id 条件（跨租户操作命中「不存在」），与 TrackAdminService 同语义。
 * <p><b>纪律</b>：本类严禁调用 Sa-Token 权限校验/业务库 DAO（@TrackDS 范围内一切 DB 访问都落埋点库），
 * 权限校验由控制器在进入本类前完成；上传操作人 id 由控制器解析后传入。
 * <p><b>上传校验链</b>：.map 后缀 → ≤{@value TrackConstants#SOURCEMAP_MAX_BYTES} 字节 → 合法 JSON 且含
 * mappings 字段 → 应用归属本租户 → 落私有存储 → upsert 元数据（同 app_key+release+filename 重传覆盖）。
 */
@Service
@TrackDS
public class TrackSourcemapService {

	private static final Logger log = LoggerFactory.getLogger(TrackSourcemapService.class);

	private final TrackSourcemapMapper sourcemapMapper;
	private final TrackAppMapper appMapper;
	private final TrackSourcemapStorage storage;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public TrackSourcemapService(TrackSourcemapMapper sourcemapMapper, TrackAppMapper appMapper,
								 TrackSourcemapStorage storage) {
		this.sourcemapMapper = sourcemapMapper;
		this.appMapper = appMapper;
		this.storage = storage;
	}

	/**
	 * 上传 sourcemap：校验链全过后写对象存储并 upsert 元数据行，返回投影（不下发存储坐标）。
	 * operatorId 为上传操作人（控制器解析登录 id 传入，落 create_by 审计）。
	 */
	public Map<String, Object> upload(MultipartFile file, String appKey, String release, Long operatorId) {
		String key = requireAppKey(appKey);
		String rel = requireRelease(release);
		// 应用归属校验（租户插件自动隔离；他租户/不存在应用一律「不存在」）
		TrackApp app = appMapper.selectOneByQuery(QueryWrapper.create().eq("app_key", key));
		if (app == null) {
			throw new ServiceException("应用不存在或无权限");
		}
		String filename = requireMapFilename(file);
		if (file.getSize() > TrackConstants.SOURCEMAP_MAX_BYTES) {
			throw new ServiceException("sourcemap 大小不能超过 20MB");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw new ServiceException("读取上传文件失败");
		}
		assertValidSourcemap(bytes);

		FileInfo info = storage.save(key, rel, filename, bytes);
		String fullKey = TrackSourcemapStorage.fullKey(info.getBasePath(), info.getPath(), info.getFilename());
		TrackSourcemap row = sourcemapMapper.selectOneByQuery(QueryWrapper.create()
			.eq("app_key", key).eq("release", rel).eq("filename", filename));
		if (row == null) {
			row = new TrackSourcemap();
			row.setAppKey(key);
			row.setRelease(rel);
			row.setFilename(filename);
			row.setStorageKey(fullKey);
			row.setStoragePlatform(info.getPlatform());
			row.setStorageBasePath(info.getBasePath() == null ? "" : info.getBasePath());
			row.setSizeBytes((long) bytes.length);
			row.setTenantId(currentTenantOrDefault());
			row.setCreateBy(operatorId);
			row.sanitizeForInsert();
			sourcemapMapper.insertSelective(row);
		} else {
			// 同键重传 = 覆盖：对象已同键覆写，元数据刷新存储坐标/体积即可
			row.setStorageKey(fullKey);
			row.setStoragePlatform(info.getPlatform());
			row.setStorageBasePath(info.getBasePath() == null ? "" : info.getBasePath());
			row.setSizeBytes((long) bytes.length);
			row.setCreateBy(operatorId);
			row.sanitizeForUpdate();
			sourcemapMapper.update(row, true);
		}
		return project(row);
	}

	/** 分页：appKey 必填、release 精确可选；投影剔除存储坐标（内部实现细节不下发） */
	public Page<Map<String, Object>> page(String appKey, String release, long pageNum, long pageSize) {
		QueryWrapper query = QueryWrapper.create().eq("app_key", requireAppKey(appKey)).orderBy("id", false);
		if (release != null && !release.isBlank()) {
			query.eq("release", release.trim());
		}
		Page<TrackSourcemap> page = sourcemapMapper.paginate(pageNum,
			Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX), query);
		List<Map<String, Object>> records = new ArrayList<>(page.getRecords().size());
		for (TrackSourcemap row : page.getRecords()) {
			records.add(project(row));
		}
		return new Page<>(records, page.getPageNumber(), page.getPageSize(), page.getTotalRow());
	}

	/** 删除：删对象（尽力而为，失败记日志留人工）+ 逻辑删行（租户插件隔离，跨租户命中「不存在」） */
	public void remove(long id) {
		TrackSourcemap row = requireRow(id);
		try {
			if (!storage.delete(row.getStoragePlatform(), row.getStorageBasePath(), row.getStorageKey())) {
				log.warn("sourcemap 对象删除返回 false：{}", row.getStorageKey());
			}
		} catch (Exception e) {
			log.warn("sourcemap 对象删除异常 {}：{}", row.getStorageKey(), e.getMessage());
		}
		sourcemapMapper.deleteByQuery(QueryWrapper.create().where("id = ?", id));
	}

	/** 读取 .map 原文（raw 端点用；存储侧异常一律「不存在或已下线」，不暴露存储内部错误形态） */
	public byte[] raw(long id) {
		TrackSourcemap row = requireRow(id);
		try {
			return storage.load(row.getStoragePlatform(), row.getStorageBasePath(), row.getStorageKey());
		} catch (Exception e) {
			log.warn("sourcemap 读取失败 id={} key={}：{}", id, row.getStorageKey(), e.getMessage());
			throw new ServiceException("sourcemap 文件不存在或存储平台已下线");
		}
	}

	// ==================== 内部工具 ====================

	/** 按 id 取行（租户插件自动隔离；跨租户/已删 → 「不存在」） */
	private TrackSourcemap requireRow(long id) {
		TrackSourcemap row = sourcemapMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", id));
		if (row == null) {
			throw new ServiceException("sourcemap 不存在或无权限");
		}
		return row;
	}

	/** 行 → 下发投影（无 storage_key/平台坐标——绝对路径不下发，读取一律走 raw 端点按 id 授权） */
	private Map<String, Object> project(TrackSourcemap row) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", row.getId());
		item.put("appKey", row.getAppKey());
		item.put("release", row.getRelease());
		item.put("filename", row.getFilename());
		item.put("sizeBytes", row.getSizeBytes());
		item.put("tenantId", row.getTenantId());
		item.put("createBy", row.getCreateBy());
		item.put("createTime", row.getCreateTime());
		item.put("updateTime", row.getUpdateTime());
		return item;
	}

	private String requireAppKey(String appKey) {
		if (appKey == null || appKey.isBlank() || appKey.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("缺少或非法 appKey");
		}
		return appKey.trim();
	}

	/** release 校验：必填、≤{@value TrackConstants#SOURCEMAP_RELEASE_MAX_LEN} 且为对象键安全字符（入对象键路径段，防路径穿越） */
	private String requireRelease(String release) {
		if (release == null || release.isBlank() || release.length() > TrackConstants.SOURCEMAP_RELEASE_MAX_LEN
			|| !TrackConstants.SOURCEMAP_PATH_SAFE.matcher(release.trim()).matches()) {
			throw new ServiceException("非法 release（字母/数字/.-_，≤128 字）");
		}
		return release.trim();
	}

	/** 文件名校验：.map 后缀（大小写不敏感）、剥离路径段、对象键安全字符、≤{@value TrackConstants#SOURCEMAP_FILENAME_MAX_LEN} */
	private String requireMapFilename(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ServiceException("上传文件为空");
		}
		String name = file.getOriginalFilename();
		if (name == null || name.isBlank()) {
			throw new ServiceException("缺少文件名");
		}
		// 老浏览器会上送全路径：剥离目录段只留文件名
		String base = name.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1);
		if (base.length() > TrackConstants.SOURCEMAP_FILENAME_MAX_LEN
			|| !base.toLowerCase().endsWith(TrackConstants.SOURCEMAP_SUFFIX)
			|| !TrackConstants.SOURCEMAP_PATH_SAFE.matcher(base).matches()) {
			throw new ServiceException("仅支持 .map 文件（文件名字母/数字/.-_，≤255 字）");
		}
		return base;
	}

	/** sourcemap 内容校验：合法 JSON 对象且含 mappings 文本字段（sourcemap v3 必要字段，前端还原解析依赖） */
	private void assertValidSourcemap(byte[] bytes) {
		JsonNode root;
		try {
			root = objectMapper.readTree(bytes);
		} catch (Exception e) {
			throw new ServiceException("sourcemap 非合法 JSON");
		}
		if (root == null || !root.isObject() || !root.path("mappings").isTextual()) {
			throw new ServiceException("非法 sourcemap：缺少 mappings 字段");
		}
	}

	/** 归属租户：当前会话租户；超管「查看全部」模式（null）落平台默认租户（与 TrackAdminService 同口径） */
	private String currentTenantOrDefault() {
		String tenant = TenantContext.current();
		return tenant == null || tenant.isBlank() ? TenantConstants.DEFAULT_TENANT_ID : tenant;
	}
}
