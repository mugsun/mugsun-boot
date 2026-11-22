package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 转化目标（埋点库）：给一个应用定的「算作达成」的条件，要么是触发了某个自定义事件，要么是访问了某个页面。
 * 目标只是一条定义，不存统计结果；转化人数、次数和转化率都是查询时按定义去事件明细里算的，
 * 所以改了条件以后，以前的数据也按新条件算。
 *
 * <p>同一个应用里目标名称不能重复（部分唯一索引 {@code uk_goal_name}，只管没删除的行）。
 */
@Table("track_goal")
public class TrackGoal extends BaseEntity {

	/** 目标属于哪个应用；建好以后不能改 */
	private String appKey;
	/** 目标名称，给人看的 */
	private String goalName;
	/** 目标类型：event 触发事件 / page 访问页面 */
	private String goalType;
	/** 事件目标的事件名；页面目标时为空 */
	private String eventName;
	/** 页面目标的页面地址，末尾可以带 * 表示这个地址开头的所有页面；事件目标时为空 */
	private String pagePath;
	/** 归属租户：跟着应用走，由服务端定，不接受提交 */
	private String tenantId;
	/** 创建人用户 id */
	private Long createBy;
	/** 最近一次修改人用户 id */
	private Long updateBy;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getGoalName() {
		return goalName;
	}

	public void setGoalName(String goalName) {
		this.goalName = goalName;
	}

	public String getGoalType() {
		return goalType;
	}

	public void setGoalType(String goalType) {
		this.goalType = goalType;
	}

	public String getEventName() {
		return eventName;
	}

	public void setEventName(String eventName) {
		this.eventName = eventName;
	}

	public String getPagePath() {
		return pagePath;
	}

	public void setPagePath(String pagePath) {
		this.pagePath = pagePath;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
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
