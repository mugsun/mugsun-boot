package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 圈选式可视化埋点规则（track 库，G104）：inspect 圈选 → 草稿确认 → /track/config 下发 → SDK 命中上报自定义事件。
 * <p>自然键 (app_key, event_name, selector, coalesce(route_path,''), coalesce(match_text,'')) 部分唯一索引
 * （uk_visual_rule，is_deleted=0）：重复圈选 = 更新而非堆行；selector 只读（改 selector = 重新圈选）。
 */
@Table("track_visual_rule")
public class TrackVisualRule extends BaseEntity {

	/** 接入应用标识 */
	private String appKey;
	/** 命中后上报的自定义事件名（须过 CUSTOM_EVENT_NAME 正则，$ 前缀必拒） */
	private String eventName;
	/** 圈选生成的 CSS selector（SDK 端已验唯一；只读，改 selector = 重新圈选） */
	private String selector;
	/** 路由模板限定；NULL = 全站生效 */
	private String routePath;
	/** 元素文本包含匹配；NULL = 不限 */
	private String matchText;
	/** 状态：1 启用 / 0 停用（停用 = 不下发不命中） */
	private Integer status;
	/** 规则来源（当前恒 visual；留列防未来手工规则混入无法区分） */
	private String source;
	/** 归属租户（服务端裁定：令牌归属租户，禁止客户端上报） */
	private String tenantId;
	private String remark;
	/** 创建操作人用户 id（草稿确认人） */
	private Long createBy;
	/** 最近更新操作人用户 id */
	private Long updateBy;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getEventName() {
		return eventName;
	}

	public void setEventName(String eventName) {
		this.eventName = eventName;
	}

	public String getSelector() {
		return selector;
	}

	public void setSelector(String selector) {
		this.selector = selector;
	}

	public String getRoutePath() {
		return routePath;
	}

	public void setRoutePath(String routePath) {
		this.routePath = routePath;
	}

	public String getMatchText() {
		return matchText;
	}

	public void setMatchText(String matchText) {
		this.matchText = matchText;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	public Long getCreateBy() {
		return createBy;
	}

	public void setCreateBy(Long createBy) {
		this.createBy = createBy;
	}

	public Long getUpdateBy() {
		return updateBy;
	}

	public void setUpdateBy(Long updateBy) {
		this.updateBy = updateBy;
	}
}
