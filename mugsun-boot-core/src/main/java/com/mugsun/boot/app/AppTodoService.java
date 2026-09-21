package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppFieldVO;
import com.mugsun.boot.app.dto.AppHandleDTO;
import com.mugsun.boot.app.dto.AppTodoDetailVO;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.boot.system.service.FlowService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.row.Row;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AppTodoService {

	private final FlowService flowService;

	public AppTodoService(FlowService flowService) {
		this.flowService = flowService;
	}

	public List<AppTodoItemVO> list() {
		return flowService.myTodo().stream().map(AppTodoService::toItem).toList();
	}

	public AppTodoDetailVO detail(Long taskId) {
		AppTodoItemVO item = list().stream()
			.filter(t -> t.taskId() == taskId)
			.findFirst()
			.orElseThrow(() -> new ServiceException("待办不存在或已办理"));
		List<AppFieldVO> fields = flattenForm(flowService.taskForm(taskId));
		return new AppTodoDetailVO(item.taskId(), item.instanceId(), item.flowName(), item.nodeName(),
			item.createTime(), fields);
	}

	public String handle(Long taskId, AppHandleDTO dto) {
		if (dto == null || dto.action() == null) {
			throw new ServiceException("请选择办理动作");
		}
		String comment = dto.comment() == null ? "" : dto.comment().trim();
		if ("pass".equals(dto.action())) {
			return flowService.pass(taskId, comment.isEmpty() ? "同意" : comment, null);
		}
		if ("reject".equals(dto.action())) {
			return flowService.rejectLast(taskId, comment.isEmpty() ? "驳回" : comment);
		}
		throw new ServiceException("不支持的办理动作");
	}

	private static AppTodoItemVO toItem(Row row) {
		long taskId = longVal(row, "taskId");
		long instanceId = longVal(row, "instanceId");
		String flowName = str(row, "flowName");
		String nodeName = str(row, "nodeName");
		String title = flowName.isEmpty() ? "待办事项" : flowName;
		return new AppTodoItemVO(taskId, instanceId, title, flowName, nodeName, str(row, "createTime"));
	}

	@SuppressWarnings("unchecked")
	private static List<AppFieldVO> flattenForm(Map<String, Object> form) {
		List<AppFieldVO> fields = new ArrayList<>();
		if (form == null || !Boolean.TRUE.equals(form.get("hasForm"))) {
			return fields;
		}
		Object dataObj = form.get("data");
		if (!(dataObj instanceof Map<?, ?> data) || data.isEmpty()) {
			return fields;
		}
		for (Map.Entry<?, ?> e : data.entrySet()) {
			if (e.getKey() == null) {
				continue;
			}
			String key = String.valueOf(e.getKey());
			if (key.isBlank()) {
				continue;
			}
			Object v = e.getValue();
			fields.add(new AppFieldVO(key, v == null ? "" : String.valueOf(v)));
		}
		return fields;
	}

	private static long longVal(Row row, String col) {
		Long v = row.getLong(col);
		if (v == null) {
			v = row.getLong(col.toUpperCase());
		}
		return v == null ? 0L : v;
	}

	private static String str(Row row, String col) {
		Object v = row.get(col);
		if (v == null) {
			v = row.get(col.toUpperCase());
		}
		return v == null ? "" : String.valueOf(v);
	}
}
