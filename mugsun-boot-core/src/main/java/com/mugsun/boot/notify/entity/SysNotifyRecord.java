package com.mugsun.boot.notify.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知发送流水：一次 fan-out 按（渠道 × 接收人）一行，batch_id 关联同一次业务事件。
 * append-only 流水不带 is_deleted（无逻辑删除语义，清理走归档/物理删除），故不继承 BaseEntity。
 */
@Table("sys_notify_record")
public class SysNotifyRecord implements Serializable {

	/** 雪花主键 */
	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;
	/** 同一次 fan-out 分组 id */
	private Long batchId;
	/** 租户编号（插入时由租户工厂填充；重试扫描据此重放租户上下文） */
	private String tenantId;
	/** 统一模板编码 */
	private String templateCode;
	/** 渠道编码 */
	private String channel;
	/** 接收用户 id（站内信必填，其余渠道可空） */
	private Long receiverId;
	/** 发送时联系方式快照 */
	private String receiverContact;
	/** 渲染后主题 */
	private String subject;
	/** 渲染后内容全量（重试保真用） */
	private String content;
	/** 渲染后内容摘要（截断，列表展示用） */
	private String contentSummary;
	/** 状态：INIT/IGNORE/SUCCESS/FAILURE/DEAD */
	private String status;
	/** 失败/忽略原因 */
	private String errorMsg;
	/** 单次投递耗时（毫秒） */
	private Long costMs;
	/** 已重试次数 */
	private Integer retryCount;
	/** 下次重试时间（FAILURE 时按线性退避计算；成功后不再生效——扫描以 status=FAILURE 为前提） */
	private LocalDateTime nextRetryTime;
	@Column(onInsertValue = "now()")
	private LocalDateTime createTime;
	@Column(onInsertValue = "now()", onUpdateValue = "now()")
	private LocalDateTime updateTime;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getBatchId() {
		return batchId;
	}

	public void setBatchId(Long batchId) {
		this.batchId = batchId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getTemplateCode() {
		return templateCode;
	}

	public void setTemplateCode(String templateCode) {
		this.templateCode = templateCode;
	}

	public String getChannel() {
		return channel;
	}

	public void setChannel(String channel) {
		this.channel = channel;
	}

	public Long getReceiverId() {
		return receiverId;
	}

	public void setReceiverId(Long receiverId) {
		this.receiverId = receiverId;
	}

	public String getReceiverContact() {
		return receiverContact;
	}

	public void setReceiverContact(String receiverContact) {
		this.receiverContact = receiverContact;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getContentSummary() {
		return contentSummary;
	}

	public void setContentSummary(String contentSummary) {
		this.contentSummary = contentSummary;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getErrorMsg() {
		return errorMsg;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public Long getCostMs() {
		return costMs;
	}

	public void setCostMs(Long costMs) {
		this.costMs = costMs;
	}

	public Integer getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(Integer retryCount) {
		this.retryCount = retryCount;
	}

	public LocalDateTime getNextRetryTime() {
		return nextRetryTime;
	}

	public void setNextRetryTime(LocalDateTime nextRetryTime) {
		this.nextRetryTime = nextRetryTime;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}

	public LocalDateTime getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(LocalDateTime updateTime) {
		this.updateTime = updateTime;
	}
}
