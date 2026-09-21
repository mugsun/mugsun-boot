package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;
import tech.powerjob.client.PowerJobClient;
import tech.powerjob.common.enums.ExecuteType;
import tech.powerjob.common.enums.ProcessorType;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.common.request.http.SaveJobInfoRequest;
import tech.powerjob.common.request.query.InstancePageQuery;
import tech.powerjob.common.response.InstanceInfoDTO;
import tech.powerjob.common.response.JobInfoDTO;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 定时任务管理：代理 PowerJob OpenAPI，任务 CRUD / 触发 / 启停 / 执行日志。
 * 处理器来自 Spring 容器中全部 BasicProcessor 实现（/processors 注册表），
 * 任务以「名称 + 处理器 + 时间表达式 + jobParams」维度维护。
 */
@RestController
@RequestMapping("/system/job")
@SaCheckLogin
public class JobController {

	private static final Logger log = LoggerFactory.getLogger(JobController.class);

	/** PowerJob OpenAPI 地址/凭据外置（默认本地联调值，生产经环境变量覆盖） */
	@org.springframework.beans.factory.annotation.Value("${powerjob.openapi.address:${powerjob.worker.server-address:127.0.0.1:7700}}")
	private String serverAddress;

	@org.springframework.beans.factory.annotation.Value("${powerjob.openapi.app-name:${powerjob.worker.app-name:mugsun}}")
	private String appName;

	@org.springframework.beans.factory.annotation.Value("${powerjob.openapi.password:${POWERJOB_OPENAPI_PASSWORD:mugsun}}")
	private String password;

	private final ApplicationContext applicationContext;

	public JobController(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	private volatile PowerJobClient client;

	private PowerJobClient client() {
		if (client == null) {
			synchronized (this) {
				if (client == null) {
					client = new PowerJobClient(serverAddress, appName, password);
				}
			}
		}
		return client;
	}

	/** OpenAPI 调用失败转友好业务错误，避免 Server 未起时整页 500 */
	private <T> T callOpenApi(java.util.function.Supplier<T> action) {
		try {
			return action.get();
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			log.warn("PowerJob OpenAPI 不可用 address={}", serverAddress, e);
			throw new ServiceException("定时任务服务不可用，请先启动 PowerJob Server（" + serverAddress + "）");
		}
	}

	/** 任务列表（过滤已删除 status=99） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/list")
	public R<List<JobInfoDTO>> list() {
		List<JobInfoDTO> jobs = callOpenApi(() -> client().fetchAllJob().getData());
		return R.data(jobs == null ? List.of()
			: jobs.stream().filter(j -> j.getStatus() == null || j.getStatus() != 99).toList());
	}

	/** 处理器注册表：Spring 容器中全部 BasicProcessor 实现（value=全限定类名，label=简单类名） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/processors")
	public R<List<Map<String, String>>> processors() {
		Map<String, BasicProcessor> beans = applicationContext.getBeansOfType(BasicProcessor.class);
		Map<String, String> classNames = new TreeMap<>();
		beans.values().forEach(bean -> classNames.put(bean.getClass().getName(), bean.getClass().getSimpleName()));
		return R.data(classNames.entrySet().stream()
			.map(e -> Map.of("value", e.getKey(), "label", e.getValue()))
			.toList());
	}

	/** 新建 / 更新任务 */
	@SaCheckPermission("sys:job:save")
	@PostMapping("/save")
	public R<Long> save(@RequestBody JobParam param) {
		String processorInfo = assertProcessorRegistered(param.processorInfo());
		SaveJobInfoRequest req = new SaveJobInfoRequest();
		if (param.id() != null) {
			req.setId(param.id());
		}
		req.setJobName(param.jobName());
		req.setJobDescription(param.jobDescription());
		req.setProcessorType(ProcessorType.BUILT_IN);
		req.setProcessorInfo(processorInfo);
		req.setJobParams(param.jobParams());
		req.setExecuteType(ExecuteType.STANDALONE);
		TimeExpressionType type = TimeExpressionType.valueOf(
			param.timeExpressionType() == null || param.timeExpressionType().isBlank() ? "API" : param.timeExpressionType());
		req.setTimeExpressionType(type);
		if (type == TimeExpressionType.CRON) {
			req.setTimeExpression(param.timeExpression());
		}
		return R.data(callOpenApi(() -> client().saveJob(req).getData()));
	}

	/** 处理器校验：必须已在注册表（Spring 容器 BasicProcessor 实现）中，防保存指向不存在类的死任务 */
	private String assertProcessorRegistered(String processorInfo) {
		if (processorInfo == null || processorInfo.isBlank()) {
			throw new ServiceException("请选择任务处理器");
		}
		String name = processorInfo.trim();
		boolean registered = applicationContext.getBeansOfType(BasicProcessor.class).values().stream()
			.anyMatch(bean -> bean.getClass().getName().equals(name));
		if (!registered) {
			throw new ServiceException("处理器未注册：" + name);
		}
		return name;
	}

	/** 立即执行一次（携带任务配置的 jobParams 作为实例参数），返回执行实例ID */
	@SaCheckPermission("sys:job:run")
	@PostMapping("/run/{jobId}")
	public R<Long> run(@PathVariable Long jobId) {
		JobInfoDTO job = callOpenApi(() -> client().fetchJob(jobId).getData());
		String instanceParams = job == null || job.getJobParams() == null ? "" : job.getJobParams();
		return R.data(callOpenApi(() -> client().runJob(jobId, instanceParams, 0L).getData()));
	}

	/** 启用任务 */
	@SaCheckPermission("sys:job:edit")
	@PostMapping("/enable/{jobId}")
	public R<Void> enable(@PathVariable Long jobId) {
		callOpenApi(() -> {
			client().enableJob(jobId);
			return null;
		});
		return R.success("已启用");
	}

	/** 停用任务 */
	@SaCheckPermission("sys:job:edit")
	@PostMapping("/disable/{jobId}")
	public R<Void> disable(@PathVariable Long jobId) {
		callOpenApi(() -> {
			client().disableJob(jobId);
			return null;
		});
		return R.success("已停用");
	}

	/** 删除任务 */
	@SaCheckPermission("sys:job:remove")
	@PostMapping("/delete/{jobId}")
	public R<Void> delete(@PathVariable Long jobId) {
		callOpenApi(() -> {
			client().deleteJob(jobId);
			return null;
		});
		return R.success("删除成功");
	}

	/** 任务执行日志（最近实例） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/instances")
	public R<List<InstanceInfoDTO>> instances(@RequestParam Long jobId) {
		InstancePageQuery query = new InstancePageQuery();
		query.setJobIdEq(jobId);
		query.setIndex(0);
		query.setPageSize(20);
		return R.data(callOpenApi(() -> client().queryInstanceInfo(query).getData().getData()));
	}

	/** 查询执行实例状态（3 运行中 / 5 成功 / 4 失败） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/status")
	public R<Integer> status(@RequestParam Long instanceId) {
		return R.data(callOpenApi(() -> client().fetchInstanceStatus(instanceId).getData()));
	}

	/** 任务参数：id 为空则新建；processorInfo 处理器全限定类名；jobParams 任务固定参数；时间表达式类型 API（手动）/ CRON（定时） */
	public record JobParam(Long id, String jobName, String jobDescription, String processorInfo, String jobParams,
						   String timeExpressionType, String timeExpression) {
	}
}
