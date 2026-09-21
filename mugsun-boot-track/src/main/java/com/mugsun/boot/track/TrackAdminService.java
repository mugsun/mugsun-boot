package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackApp;
import com.mugsun.boot.track.entity.TrackEventDef;
import com.mugsun.boot.track.mapper.TrackAppMapper;
import com.mugsun.boot.track.mapper.TrackEventDefMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 埋点接入管理服务（track_app / track_event_def 读写唯一出口）：类级 {@link TrackDS} 路由埋点独立库，
 * Flex 租户行级插件对两表自动拼 tenant_id 条件（分页/按 id 读写均带隔离，跨租户操作命中「不存在」）。
 * <p><b>纪律</b>：本类严禁调用 Sa-Token 权限校验/业务库 DAO（@TrackDS 范围内一切 DB 访问都落埋点库，
 * 权限校验由控制器在进入本类前完成）。
 * <p>变更/删除后主动失效 {@link TrackAppService} 本地缓存（否则采集端最坏 30s 后才感知停用）；
 * 事件定义变更后同样主动失效 {@link TrackEventDefService} 停用判定缓存（G105）。
 */
@Service
@TrackDS
public class TrackAdminService {

	/** app_key 生成重试上限（随机段撞唯一索引概率极低，超限即故障） */
	private static final int APP_KEY_GEN_MAX_ATTEMPTS = 5;

	private final TrackAppMapper appMapper;
	private final TrackEventDefMapper eventDefMapper;
	private final TrackAppService appService;
	private final TrackEventDefService eventDefService;

	public TrackAdminService(TrackAppMapper appMapper, TrackEventDefMapper eventDefMapper,
							 TrackAppService appService, TrackEventDefService eventDefService) {
		this.appMapper = appMapper;
		this.eventDefMapper = eventDefMapper;
		this.appService = appService;
		this.eventDefService = eventDefService;
	}

	// ==================== 应用管理 ====================

	/** 应用分页（本租户行级隔离由 Flex 插件自动拼条件） */
	public Page<TrackApp> appPage(long pageNum, long pageSize, String appName) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (appName != null && !appName.isBlank()) {
			query.and("app_name LIKE ?", "%" + appName.trim() + "%");
		}
		return appMapper.paginate(pageNum, Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX), query);
	}

	/**
	 * 新增/编辑应用：无 id=新增（服务端生成 app_key、裁定居租户）；有 id=编辑（app_key/tenant_id 不可改）。
	 * 审计字段一律服务端清洗（sanitizeForInsert/Update 剥离 create_time/is_deleted 等伪造输入）。
	 * 可编辑字段清空语义：传空串清空，字段缺省（null）保持不变。
	 */
	public TrackApp appSubmit(TrackApp body) {
		TrackApp app;
		if (body.getId() == null) {
			app = new TrackApp();
			app.setAppKey(generateAppKey());
			app.setTenantId(currentTenantOrDefault());
			app.setAppName(requireAppName(body.getAppName()));
			app.setPlatform(normalizePlatform(body.getPlatform()));
			app.setSampleRate(validateSampleRate(body.getSampleRate()));
			app.setEnabled(normalizeEnabled(body.getEnabled()));
			app.setMaskSelectors(body.getMaskSelectors());
			app.setRetentionDays(validateRetentionDays(body.getRetentionDays()));
			app.setReplayEnabled(body.getReplayEnabled() == null ? 0 : normalizeSwitch(body.getReplayEnabled(), "replayEnabled"));
			app.setReplaySampleRate(validateReplaySampleRate(body.getReplaySampleRate()));
			app.setReplayRetentionDays(validateReplayRetentionDays(body.getReplayRetentionDays()));
			app.setAlertEnabled(body.getAlertEnabled() == null ? 0 : normalizeSwitch(body.getAlertEnabled(), "alertEnabled"));
			app.setAlertThreshold(validateAlertThreshold(body.getAlertThreshold()));
			app.setApiMonitorEnabled(body.getApiMonitorEnabled() == null ? 0 : normalizeSwitch(body.getApiMonitorEnabled(), "apiMonitorEnabled"));
			app.setApiBodyEnabled(body.getApiBodyEnabled() == null ? 0 : normalizeSwitch(body.getApiBodyEnabled(), "apiBodyEnabled"));
			app.setApiBodyMaskEnabled(body.getApiBodyMaskEnabled() == null ? 0 : normalizeSwitch(body.getApiBodyMaskEnabled(), "apiBodyMaskEnabled"));
			app.setApiBodyRetentionDays(validateApiBodyRetentionDays(body.getApiBodyRetentionDays()));
			app.setGeoEnabled(body.getGeoEnabled() == null ? 0 : normalizeSwitch(body.getGeoEnabled(), "geoEnabled"));
			app.setRemark(body.getRemark());
			app.sanitizeForInsert();
			appMapper.insertSelective(app);
		} else {
			app = appMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", body.getId()));
			if (app == null) {
				throw new ServiceException("应用不存在或无权限");
			}
			// appKey/tenantId 服务端字段，客户端提交一律忽略（防伪造接管他应用数据）
			if (body.getAppName() != null) {
				app.setAppName(requireAppName(body.getAppName()));
			}
			if (body.getPlatform() != null) {
				app.setPlatform(normalizePlatform(body.getPlatform()));
			}
			if (body.getSampleRate() != null) {
				app.setSampleRate(validateSampleRate(body.getSampleRate()));
			}
			if (body.getEnabled() != null) {
				app.setEnabled(normalizeEnabled(body.getEnabled()));
			}
			if (body.getMaskSelectors() != null) {
				app.setMaskSelectors(body.getMaskSelectors());
			}
			if (body.getRetentionDays() != null) {
				app.setRetentionDays(validateRetentionDays(body.getRetentionDays()));
			}
			if (body.getReplayEnabled() != null) {
				app.setReplayEnabled(normalizeSwitch(body.getReplayEnabled(), "replayEnabled"));
			}
			if (body.getReplaySampleRate() != null) {
				app.setReplaySampleRate(validateReplaySampleRate(body.getReplaySampleRate()));
			}
			if (body.getReplayRetentionDays() != null) {
				app.setReplayRetentionDays(validateReplayRetentionDays(body.getReplayRetentionDays()));
			}
			if (body.getAlertEnabled() != null) {
				app.setAlertEnabled(normalizeSwitch(body.getAlertEnabled(), "alertEnabled"));
			}
			if (body.getAlertThreshold() != null) {
				app.setAlertThreshold(validateAlertThreshold(body.getAlertThreshold()));
			}
			if (body.getApiMonitorEnabled() != null) {
				app.setApiMonitorEnabled(normalizeSwitch(body.getApiMonitorEnabled(), "apiMonitorEnabled"));
			}
			if (body.getApiBodyEnabled() != null) {
				app.setApiBodyEnabled(normalizeSwitch(body.getApiBodyEnabled(), "apiBodyEnabled"));
			}
			if (body.getApiBodyMaskEnabled() != null) {
				app.setApiBodyMaskEnabled(normalizeSwitch(body.getApiBodyMaskEnabled(), "apiBodyMaskEnabled"));
			}
			if (body.getApiBodyRetentionDays() != null) {
				app.setApiBodyRetentionDays(validateApiBodyRetentionDays(body.getApiBodyRetentionDays()));
			}
			if (body.getGeoEnabled() != null) {
				app.setGeoEnabled(normalizeSwitch(body.getGeoEnabled(), "geoEnabled"));
			}
			if (body.getRemark() != null) {
				app.setRemark(body.getRemark());
			}
			app.sanitizeForUpdate();
			appMapper.update(app, true);
		}
		// 采集端本地缓存主动失效（停用/删除即时生效，不等 30s TTL）
		appService.evict(app.getAppKey());
		return app;
	}

	/** 删除应用（逻辑删除；缓存即时失效后采集端拒收） */
	public void appRemove(long id) {
		TrackApp app = appMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", id));
		if (app == null) {
			throw new ServiceException("应用不存在或无权限");
		}
		appMapper.deleteByQuery(QueryWrapper.create().where("id = ?", id));
		appService.evict(app.getAppKey());
	}

	// ==================== 事件定义治理 ====================

	/** 事件定义分页：appKey 必填；eventName 模糊 / status 精确可选 */
	public Page<TrackEventDef> eventDefPage(String appKey, String eventName, Integer status, long pageNum, long pageSize) {
		QueryWrapper query = QueryWrapper.create().where("app_key = ?", appKey).orderBy("last_seen_time", false);
		if (eventName != null && !eventName.isBlank()) {
			query.and("event_name LIKE ?", "%" + eventName.trim() + "%");
		}
		if (status != null) {
			query.and("status = ?", status);
		}
		return eventDefMapper.paginate(pageNum, Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX), query);
	}

	/** 事件定义认领：仅 displayName/description/status/owner 可改（事件名/归属由采集端自动注册，防伪造） */
	public TrackEventDef eventDefSubmit(TrackEventDef body) {
		if (body.getId() == null) {
			throw new ServiceException("缺少 id（事件定义由采集端自动注册，仅支持认领编辑）");
		}
		TrackEventDef def = eventDefMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", body.getId()));
		if (def == null) {
			throw new ServiceException("事件定义不存在或无权限");
		}
		if (body.getDisplayName() != null) {
			def.setDisplayName(body.getDisplayName());
		}
		if (body.getDescription() != null) {
			def.setDescription(body.getDescription());
		}
		if (body.getStatus() != null) {
			def.setStatus(normalizeSwitch(body.getStatus(), "status"));
		}
		if (body.getOwner() != null) {
			def.setOwner(body.getOwner());
		}
		def.sanitizeForUpdate();
		eventDefMapper.update(def, true);
		// 采集端停用判定本地缓存主动失效（G105：停用/启用即时生效，不等 30s TTL）
		eventDefService.evict(def.getAppKey(), def.getEventName());
		return def;
	}

	// ==================== 内部工具 ====================

	/** app_key 服务端生成：{@value TrackConstants#APP_KEY_PREFIX} 前缀 + 24 位随机 hex（唯一索引冲突重试） */
	private String generateAppKey() {
		for (int i = 0; i < APP_KEY_GEN_MAX_ATTEMPTS; i++) {
			String key = TrackConstants.APP_KEY_PREFIX
				+ IdUtil.fastSimpleUUID().substring(0, TrackConstants.APP_KEY_RANDOM_LEN);
			if (appMapper.selectCountByQuery(QueryWrapper.create().eq("app_key", key)) == 0) {
				return key;
			}
		}
		throw new ServiceException("app_key 生成冲突，请重试");
	}

	/** 新增应用归属租户：当前会话租户；超管「查看全部」模式（null）落平台默认租户 */
	private String currentTenantOrDefault() {
		String tenant = TenantContext.current();
		return tenant == null || tenant.isBlank() ? TenantConstants.DEFAULT_TENANT_ID : tenant;
	}

	private String requireAppName(String appName) {
		if (appName == null || appName.isBlank() || appName.length() > 64) {
			throw new ServiceException("应用名称必填且 ≤64 字");
		}
		return appName.trim();
	}

	private String normalizePlatform(String platform) {
		if (platform == null || platform.isBlank()) {
			return TrackConstants.PLATFORM_WEB;
		}
		String p = platform.trim();
		if (p.length() > 16 || !p.matches("^[a-z]+$")) {
			throw new ServiceException("非法 platform（小写字母，≤16 字）");
		}
		return p;
	}

	/** 采样率 1..100（%；越界 400 防 0 采样/放大失真） */
	private int validateSampleRate(Integer sampleRate) {
		if (sampleRate == null) {
			return 100;
		}
		if (sampleRate < 1 || sampleRate > 100) {
			throw new ServiceException("采样率须 1..100");
		}
		return sampleRate;
	}

	/** 保留天数 1..3650（同 LogCleanJob 钳制口径：0/负值会清光数据） */
	private int validateRetentionDays(Integer retentionDays) {
		if (retentionDays == null) {
			return TrackConstants.DEFAULT_RETENTION_DAYS;
		}
		if (retentionDays < 1 || retentionDays > 3650) {
			throw new ServiceException("保留天数须 1..3650");
		}
		return retentionDays;
	}

	/** 回放采样率 0..100（%；0 合法 = 仅 $error 会话强传，见 SDK 回放插件语义），缺省 10 */
	private int validateReplaySampleRate(Integer sampleRate) {
		if (sampleRate == null) {
			return 10;
		}
		if (sampleRate < 0 || sampleRate > 100) {
			throw new ServiceException("回放采样率须 0..100");
		}
		return sampleRate;
	}

	/** 回放保留天数 1..30（回放体量远大于事件流，钳短上限防误配长保留撑爆对象存储），缺省 14 */
	private int validateReplayRetentionDays(Integer retentionDays) {
		if (retentionDays == null) {
			return TrackConstants.REPLAY_DEFAULT_RETENTION_DAYS;
		}
		if (retentionDays < 1 || retentionDays > 30) {
			throw new ServiceException("回放保留天数须 1..30");
		}
		return retentionDays;
	}

	/** 同指纹告警频次阈值 1..{@value TrackConstants#ALERT_THRESHOLD_MAX}（次/10 分钟窗），缺省
	 *  {@value TrackConstants#DEFAULT_ALERT_THRESHOLD}；0=永不触发、超大=形同关闭，均按误配拦截 */
	private int validateAlertThreshold(Integer threshold) {
		if (threshold == null) {
			return TrackConstants.DEFAULT_ALERT_THRESHOLD;
		}
		if (threshold < 1 || threshold > TrackConstants.ALERT_THRESHOLD_MAX) {
			throw new ServiceException("告警阈值须 1.." + TrackConstants.ALERT_THRESHOLD_MAX);
		}
		return threshold;
	}

	/** 响应体保留天数 1..{@value TrackConstants#API_BODY_MAX_RETENTION_DAYS}（同回放保留期钳制口径：体量远大于事件流），缺省
	 *  {@value TrackConstants#API_BODY_DEFAULT_RETENTION_DAYS} */
	private int validateApiBodyRetentionDays(Integer retentionDays) {
		if (retentionDays == null) {
			return TrackConstants.API_BODY_DEFAULT_RETENTION_DAYS;
		}
		if (retentionDays < 1 || retentionDays > TrackConstants.API_BODY_MAX_RETENTION_DAYS) {
			throw new ServiceException("响应体保留天数须 1.." + TrackConstants.API_BODY_MAX_RETENTION_DAYS);
		}
		return retentionDays;
	}

	private int normalizeEnabled(Integer enabled) {
		if (enabled == null) {
			return 1;
		}
		return normalizeSwitch(enabled, "enabled");
	}

	private int normalizeSwitch(Integer value, String field) {
		if (value == null || (value != 0 && value != 1)) {
			throw new ServiceException(field + " 仅支持 0/1");
		}
		return value;
	}
}
