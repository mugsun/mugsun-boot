package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.boot.system.service.FlowService;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 按用户编号取待办：取的是流程那边这个用户的待办，一行转成一条，顺序不变。不需要登录态，也不需要数据库。
 */
class AppTodoServiceTest {

	private final FlowService flowService = mock(FlowService.class);

	private final AppTodoService service = new AppTodoService(flowService);

	@Test
	void listsTheTodosOfTheGivenUser() {
		when(flowService.todoOf(7L)).thenReturn(List.of(
			row(31L, 21L, "请假", "部门经理审批", "2026-11-22 10:00:00"),
			row(30L, 20L, "报销", "财务审核", "2026-11-21 09:00:00")));

		List<AppTodoItemVO> todos = service.listOf(7L);

		assertThat(todos).containsExactly(
			new AppTodoItemVO(31L, 21L, "请假", "请假", "部门经理审批", "2026-11-22 10:00:00"),
			new AppTodoItemVO(30L, 20L, "报销", "报销", "财务审核", "2026-11-21 09:00:00"));
		verify(flowService).todoOf(7L);
		verifyNoMoreInteractions(flowService);
	}

	@Test
	void aTodoWithoutFlowNameGetsADefaultTitle() {
		Row row = new Row();
		row.put("taskId", 31L);
		row.put("instanceId", 21L);
		when(flowService.todoOf(7L)).thenReturn(List.of(row));

		assertThat(service.listOf(7L)).containsExactly(new AppTodoItemVO(31L, 21L, "待办事项", "", "", ""));
	}

	@Test
	void readsColumnsThatComeBackInUpperCase() {
		Row row = new Row();
		row.put("TASKID", 31L);
		row.put("INSTANCEID", 21L);
		row.put("FLOWNAME", "请假");
		row.put("NODENAME", "部门经理审批");
		row.put("CREATETIME", "2026-11-22 10:00:00");
		when(flowService.todoOf(7L)).thenReturn(List.of(row));

		assertThat(service.listOf(7L)).containsExactly(
			new AppTodoItemVO(31L, 21L, "请假", "请假", "部门经理审批", "2026-11-22 10:00:00"));
	}

	@Test
	void nobodyToHandleMeansAnEmptyList() {
		when(flowService.todoOf(7L)).thenReturn(List.of());

		assertThat(service.listOf(7L)).isEmpty();
	}

	private static Row row(long taskId, long instanceId, String flowName, String nodeName, String createTime) {
		Row row = new Row();
		row.put("taskId", taskId);
		row.put("instanceId", instanceId);
		row.put("flowName", flowName);
		row.put("nodeName", nodeName);
		row.put("createTime", createTime);
		return row;
	}
}
