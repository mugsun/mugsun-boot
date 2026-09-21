package com.mugsun.boot.system.service;

import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.listener.GlobalListener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * warm-flow 全局监听器（单例 @Component，引擎自动装配）：在 assignment 阶段（flow_user 落库前、含实例上下文）
 * 解析需上下文的候选人占位并做兜底。
 * <ul><li>{@code initiator} → 实例发起人 id（发起人本人审批）</li>
 * <li>{@code deptLeader} → 发起人所在部门负责人</li>
 * <li>兜底①：候选为空 → 超管（再空→门控哨兵，fail-closed，防"空办理人=人人可批")</li>
 * <li>兜底②：办理人仅发起人本人 → 追加超管，防自批卡死</li></ul>
 */
@Component
public class MugsunFlowListener implements GlobalListener {

	private final HandlerSelectService selectService;

	public MugsunFlowListener(HandlerSelectService selectService) {
		this.selectService = selectService;
	}

	@Override
	public void assignment(ListenerVariable variable) {
		Instance instance = variable.getInstance();
		String initiator = instance == null ? null : instance.getCreateBy();
		List<Task> tasks = variable.getNextTasks();
		if (tasks == null) {
			return;
		}
		for (Task task : tasks) {
			// 完整解析：占位（发起人/部门负责人）→ 实际用户 + 兜底（空候选/==发起人）
			task.setPermissionList(selectService.resolveHandlers(task.getPermissionList(), initiator));
		}
	}
}
