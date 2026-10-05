package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.service.JobServerRegistry;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.*;
import tech.powerjob.client.PowerJobClient;
import tech.powerjob.common.enums.ExecuteType;
import tech.powerjob.common.enums.ProcessorType;
import tech.powerjob.common.enums.TimeExpressionType;
import tech.powerjob.common.request.http.SaveJobInfoRequest;
import tech.powerjob.common.request.query.InstancePageQuery;
import tech.powerjob.common.response.InstanceInfoDTO;
import tech.powerjob.common.response.JobInfoDTO;
import tech.powerjob.common.response.ResultDTO;
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
	private final JobServerRegistry jobServerRegistry;

	public JobController(ApplicationContext applicationContext, JobServerRegistry jobServerRegistry) {
		this.applicationContext = applicationContext;
		this.jobServerRegistry = jobServerRegistry;
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

	private PowerJobClient client(Long serverId) {
		return serverId == null ? client() : jobServerRegistry.openClient(serverId);
	}

	/** OpenAPI 调用失败转友好业务错误，避免 Server 未起时整页 500 */
	private <T> T callOpenApi(java.util.function.Supplier<T> action) {
		return callOpenApi(null, action);
	}

	private <T> T callOpenApi(Long serverId, java.util.function.Supplier<T> action) {
		try {
			return action.get();
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			log.warn("任务服务调用失败 serverId={} err={}", serverId, e.getClass().getSimpleName());
			if (serverId == null) {
				throw new ServiceException("定时任务服务暂时连不上。请先确认本机调度服务已启动，或到任务服务页登记一个可以访问的地址");
			}
			throw new ServiceException("任务服务暂时连不上。请到任务服务页确认地址可以访问后再试");
		}
	}

	/** 任务列表（过滤已删除 status=99） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/list")
	public R<List<JobInfoDTO>> list(@RequestParam(required = false) Long serverId,
								   @RequestParam(required = false) String name) {
		List<JobInfoDTO> jobs = callOpenApi(serverId, () -> client(serverId).fetchAllJob().getData());
		String keyword = name == null ? "" : name.trim();
		return R.data(jobs == null ? List.of()
			: jobs.stream()
				.filter(j -> j.getStatus() == null || j.getStatus() != 99)
				.filter(j -> keyword.isEmpty() || (j.getJobName() != null && j.getJobName().contains(keyword)))
				.toList());
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
	@RepeatSubmit
	@PostMapping("/save")
	public R<Void> save(@RequestBody JobParam param) {
		if (param == null) {
			throw new ServiceException("请填写任务名称。用来在列表里区分");
		}
		String jobName = param.jobName() == null ? "" : param.jobName().trim();
		if (jobName.isEmpty()) {
			throw new ServiceException("请填写任务名称。用来在列表里区分");
		}
		if (jobName.length() > 128) {
			throw new ServiceException("任务名称请控制在 128 个字以内");
		}
		String description = param.jobDescription() == null ? "" : param.jobDescription().trim();
		if (description.length() > 255) {
			throw new ServiceException("任务描述请控制在 255 个字以内");
		}
		String jobParams = param.jobParams() == null ? "" : param.jobParams();
		if (jobParams.length() > 1000) {
			throw new ServiceException("任务参数请控制在 1000 个字以内");
		}
		String rawType = param.timeExpressionType() == null || param.timeExpressionType().isBlank()
			? "API" : param.timeExpressionType().trim();
		if (!"API".equals(rawType) && !"CRON".equals(rawType)) {
			throw new ServiceException("触发方式请选择手动触发或定时");
		}
		String cron = param.timeExpression() == null ? "" : param.timeExpression().trim();
		if ("CRON".equals(rawType) && cron.isEmpty()) {
			throw new ServiceException("请填写 CRON 表达式。按「秒 分 时 日 月 周」写，例如 0 0 2 * * ?");
		}
		if ("CRON".equals(rawType) && !CronExpression.isValidExpression(cron)) {
			throw new ServiceException("CRON 表达式不对。请按「秒 分 时 日 月 周」填写，例如 0 0 2 * * ?");
		}
		String processorInfo = assertProcessorRegistered(param.processorInfo());
		SaveJobInfoRequest req = new SaveJobInfoRequest();
		if (param.id() != null) {
			req.setId(param.id());
		}
		req.setJobName(jobName);
		req.setJobDescription(description);
		req.setProcessorType(ProcessorType.BUILT_IN);
		req.setProcessorInfo(processorInfo);
		req.setJobParams(jobParams);
		req.setExecuteType(ExecuteType.STANDALONE);
		TimeExpressionType type = TimeExpressionType.valueOf(rawType);
		req.setTimeExpressionType(type);
		if (type == TimeExpressionType.CRON) {
			req.setTimeExpression(cron);
		}
		Long serverId = param.serverId();
		boolean creating = param.id() == null;
		callOpenApi(serverId, () -> {
			List<JobInfoDTO> jobs = client(serverId).fetchAllJob().getData();
			if (jobs != null) {
				boolean duplicated = jobs.stream().anyMatch(job -> alive(job)
					&& jobName.equals(job.getJobName())
					&& (param.id() == null || !param.id().equals(job.getId())));
				if (duplicated) {
					throw new ServiceException("已经有叫「" + jobName + "」的任务。请换一个名称，或编辑原来那个");
				}
			}
			ResultDTO<Long> saved = client(serverId).saveJob(req);
			if (saved == null || !saved.isSuccess() || saved.getData() == null) {
				throw new ServiceException("这次没有保存成功。请确认名称、处理器和触发方式后再试");
			}
			return saved.getData();
		});
		return R.success(creating
			? "已创建。可以立即执行，或改成定时触发"
			: "已保存。下次触发会用这份配置");
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
			throw new ServiceException("这个处理器没有注册。请从列表里重新选择");
		}
		return name;
	}

	/** 立即执行一次（携带任务配置的 jobParams 作为实例参数） */
	@SaCheckPermission("sys:job:run")
	@RepeatSubmit
	@PostMapping("/run/{jobId}")
	public R<Void> run(@PathVariable Long jobId, @RequestParam(required = false) Long serverId) {
		callOpenApi(serverId, () -> {
			JobInfoDTO job = requireJob(serverId, jobId, "这个任务不存在。请刷新列表后再执行");
			String instanceParams = job.getJobParams() == null ? "" : job.getJobParams();
			ResultDTO<Long> ran = client(serverId).runJob(jobId, instanceParams, 0L);
			if (ran == null || !ran.isSuccess() || ran.getData() == null) {
				throw new ServiceException("这次没有触发成功。请刷新列表，确认任务还在后再试");
			}
			return ran.getData();
		});
		return R.success("已触发。可以打开日志看这次执行");
	}

	/** 启用任务 */
	@SaCheckPermission("sys:job:edit")
	@RepeatSubmit
	@PostMapping("/enable/{jobId}")
	public R<Void> enable(@PathVariable Long jobId, @RequestParam(required = false) Long serverId) {
		String jobName = label(callOpenApi(serverId, () -> requireJob(serverId, jobId, "这个任务不存在。请刷新列表后再启用").getJobName()));
		ResultDTO<Void> enabled = callOpenApi(serverId, () -> client(serverId).enableJob(jobId));
		if (enabled == null || !enabled.isSuccess()) {
			throw new ServiceException("这次没有启用成功。请刷新列表后再试");
		}
		return R.success("已启用「" + jobName + "」。到了触发时间会自动执行");
	}

	/** 停用任务 */
	@SaCheckPermission("sys:job:edit")
	@RepeatSubmit
	@PostMapping("/disable/{jobId}")
	public R<Void> disable(@PathVariable Long jobId, @RequestParam(required = false) Long serverId) {
		String jobName = label(callOpenApi(serverId, () -> requireJob(serverId, jobId, "这个任务不存在。请刷新列表后再停用").getJobName()));
		ResultDTO<Void> disabled = callOpenApi(serverId, () -> client(serverId).disableJob(jobId));
		if (disabled == null || !disabled.isSuccess()) {
			throw new ServiceException("这次没有停用成功。请刷新列表后再试");
		}
		return R.success("已停用「" + jobName + "」。它不会再按计划触发");
	}

	/** 删除任务 */
	@SaCheckPermission("sys:job:remove")
	@RepeatSubmit
	@PostMapping("/delete/{jobId}")
	public R<Void> delete(@PathVariable Long jobId, @RequestParam(required = false) Long serverId) {
		String jobName = label(callOpenApi(serverId, () -> requireJob(serverId, jobId, "这个任务不存在。请刷新列表后再删除").getJobName()));
		ResultDTO<Void> deleted = callOpenApi(serverId, () -> client(serverId).deleteJob(jobId));
		if (deleted == null || !deleted.isSuccess()) {
			throw new ServiceException("这次没有删除成功。请刷新列表，确认任务还在后再试");
		}
		return R.success("已删除「" + jobName + "」。它不会再被触发");
	}

	private JobInfoDTO requireJob(Long serverId, Long jobId, String missing) {
		ResultDTO<JobInfoDTO> found = client(serverId).fetchJob(jobId);
		if (found == null || !found.isSuccess() || found.getData() == null || !alive(found.getData())) {
			throw new ServiceException(missing);
		}
		return found.getData();
	}

	private boolean alive(JobInfoDTO job) {
		return job.getStatus() == null || job.getStatus() != 99;
	}

	private String label(String jobName) {
		return jobName == null || jobName.isBlank() ? "这个任务" : jobName;
	}

	/** 任务执行日志（最近实例） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/instances")
	public R<List<InstanceInfoDTO>> instances(@RequestParam Long jobId,
											 @RequestParam(required = false) Long serverId) {
		InstancePageQuery query = new InstancePageQuery();
		query.setJobIdEq(jobId);
		query.setIndex(0);
		query.setPageSize(20);
		return R.data(callOpenApi(serverId, () -> client(serverId).queryInstanceInfo(query).getData().getData()));
	}

	/** 查询执行实例状态（3 运行中 / 5 成功 / 4 失败） */
	@SaCheckPermission("sys:job:list")
	@GetMapping("/status")
	public R<Integer> status(@RequestParam Long instanceId, @RequestParam(required = false) Long serverId) {
		return R.data(callOpenApi(serverId, () -> client(serverId).fetchInstanceStatus(instanceId).getData()));
	}

	/** 任务参数：id 为空则新建；processorInfo 处理器全限定类名；jobParams 任务固定参数；时间表达式类型 API（手动）/ CRON（定时） */
	public record JobParam(Long id, String jobName, String jobDescription, String processorInfo, String jobParams,
						   String timeExpressionType, String timeExpression, Long serverId) {
	}
}
