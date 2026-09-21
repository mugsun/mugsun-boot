package com.mugsun.boot.notify;

import com.mugsun.boot.notify.api.NotifyReceiver;
import com.mugsun.boot.notify.api.NotifySendApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@link NotifySendApi} 本地实现：业务模块只依赖 Api 接口，实现可替换为远程调用（微服务预留）。
 */
@Component
public class LocalNotifySendApi implements NotifySendApi {

	private final NotifyService notifyService;

	public LocalNotifySendApi(NotifyService notifyService) {
		this.notifyService = notifyService;
	}

	@Override
	public void send(String templateCode, List<NotifyReceiver> receivers, Map<String, String> params, String... channels) {
		notifyService.send(templateCode, receivers, params, channels);
	}
}
