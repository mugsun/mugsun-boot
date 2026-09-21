package com.mugsun.boot.app;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.app.dto.AppHandleDTO;
import com.mugsun.boot.app.dto.AppTodoDetailVO;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/todos")
@SaCheckLogin
public class AppTodoController {

	private final AppTodoService todoService;

	public AppTodoController(AppTodoService todoService) {
		this.todoService = todoService;
	}

	@GetMapping
	public R<List<AppTodoItemVO>> list() {
		return R.data(todoService.list());
	}

	@GetMapping("/{taskId}")
	public R<AppTodoDetailVO> detail(@PathVariable Long taskId) {
		return R.data(todoService.detail(taskId));
	}

	@RepeatSubmit
	@PostMapping("/{taskId}/handle")
	public R<String> handle(@PathVariable Long taskId, @RequestBody(required = false) AppHandleDTO dto) {
		return R.data(todoService.handle(taskId, dto));
	}
}
