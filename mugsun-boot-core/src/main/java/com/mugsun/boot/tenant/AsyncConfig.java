package com.mugsun.boot.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.util.concurrent.Executor;

/**
 * @Async 执行器：JDK21 虚拟线程 + 租户上下文透传装饰器。
 * 覆盖默认异步执行器，使所有 @Async 方法在虚拟线程上运行且携带提交线程的租户上下文。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

	@Override
	public Executor getAsyncExecutor() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mugsun-async-");
		executor.setVirtualThreads(true);
		executor.setTaskDecorator(new TenantTaskDecorator());
		return executor;
	}
}
