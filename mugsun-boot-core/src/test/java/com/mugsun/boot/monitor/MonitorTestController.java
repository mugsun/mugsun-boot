package com.mugsun.boot.monitor;

import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * G90 集成测试探针端点（仅测试源集）：受检异常触发全局兜底 500、可控耗时造慢接口。
 * 无任何鉴权注解（公共端点），不标注任何 Swagger/OperationLog 注解——访问日志标题回退链须容忍缺失退化为 uri。
 */
@RestController
@RequestMapping("/it/monitor")
public class MonitorTestController {

	/** 受检异常：触发 GlobalExceptionHandler 兜底 → 错误日志落库 + 访问日志异常摘要 */
	@GetMapping("/boom")
	public R<String> boom() {
		throw new IllegalStateException("IT 受检异常 boom");
	}

	/** 可控耗时：配合调低的 slow-ms 阈值造慢接口（上限 5s 防滥用） */
	@GetMapping("/sleep")
	public R<String> sleep(@RequestParam(defaultValue = "200") long ms) throws InterruptedException {
		Thread.sleep(Math.min(ms, 5000));
		return R.data("ok");
	}
}
