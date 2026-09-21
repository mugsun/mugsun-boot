package com.mugsun.boot.notify.api;

import java.util.List;
import java.util.Map;

/**
 * 通知发送门面：业务模块只依赖本接口（本地/微服务实现可替换，微服务预留）。
 * <p>按统一模板渲染（缺参 fail-fast）后多渠道 fan-out，逐（渠道 × 接收人）留发送流水。
 */
public interface NotifySendApi {

	/**
	 * 多渠道发送：按模板渲染后 fan-out 到指定渠道。
	 *
	 * @param templateCode 统一模板编码（sys_notify_template，须启用）
	 * @param receivers    接收人列表（站内信取 userId，邮件取 email，短信取 phone）
	 * @param params       模板占位参数（缺 required_params 中任意键即抛业务异常）
	 * @param channels     目标渠道编码；为空时按模板默认 channels 投递
	 */
	void send(String templateCode, List<NotifyReceiver> receivers, Map<String, String> params, String... channels);
}
