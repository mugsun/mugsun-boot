package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysJobServer;
import com.mugsun.boot.system.mapper.SysJobServerMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.powerjob.client.PowerJobClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 任务服务登记与同步。口令只落库，列表和详情都不回传。
 */
@Service
public class JobServerRegistry {

	private static final Logger log = LoggerFactory.getLogger(JobServerRegistry.class);
	private static final Pattern URL = Pattern.compile("^https?://[A-Za-z0-9][A-Za-z0-9._-]*(:\\d{1,5})?$");
	private static final Pattern APP = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
	private static final String UNREACHABLE = "任务服务暂时连不上。请确认地址可以访问，然后重新同步。";
	private static final String REJECTED = "任务服务没有接受这次登记。请核对应用名称和口令后再同步。";
	private static final String SYNCED = "已同步到任务服务";

	private final SysJobServerMapper serverMapper;
	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	public JobServerRegistry(SysJobServerMapper serverMapper) {
		this.serverMapper = serverMapper;
	}

	public Page<SysJobServer> page(long pageNum, long pageSize, String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("server_name", name.trim());
		}
		return serverMapper.paginate(pageNum, Math.min(pageSize, 100), query);
	}

	public List<SysJobServer> listAll() {
		return serverMapper.selectListByQuery(QueryWrapper.create().orderBy("id", true));
	}

	public SysJobServer require(Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一套任务服务后再操作");
		}
		SysJobServer row = serverMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这套任务服务不存在，或属于其他租户。请刷新列表后再试");
		}
		return row;
	}

	public PowerJobClient openClient(Long id) {
		SysJobServer row = require(id);
		return new PowerJobClient(openApiAddress(row.getServerUrl()), row.getAppName(), row.getAppPassword());
	}

	public Map<String, Object> save(Long id, String name, String url, String appName, String password, String remark) {
		String serverName = requireText(name, "请填写服务名称", 64, "服务名称请控制在 64 字以内");
		String serverUrl = normalizeUrl(url);
		String app = normalizeApp(appName);
		String note = remark == null ? "" : remark.trim();
		if (note.length() > 200) {
			throw new ServiceException("备注请控制在 200 字以内");
		}
		QueryWrapper dup = QueryWrapper.create().eq("server_name", serverName);
		if (id != null) {
			dup.ne("id", id);
		}
		if (serverMapper.selectCountByQuery(dup) > 0) {
			throw new ServiceException("服务名称已存在，请换一个");
		}
		SysJobServer row;
		if (id == null) {
			String secret = requireText(password, "请填写应用口令。保存后不会再显示，修改时留空则沿用原来的口令", 64,
				"应用口令请控制在 64 字以内");
			row = new SysJobServer();
			row.setTenantId(TenantContext.current());
			row.setServerName(serverName);
			row.setServerUrl(serverUrl);
			row.setAppName(app);
			row.setAppPassword(secret);
			row.setRemark(note);
			row.sanitizeForInsert();
			applySync(row);
			serverMapper.insert(row);
		} else {
			row = require(id);
			if (password != null && !password.isBlank()) {
				if (password.trim().length() > 64) {
					throw new ServiceException("应用口令请控制在 64 字以内");
				}
				row.setAppPassword(password.trim());
			}
			row.setServerName(serverName);
			row.setServerUrl(serverUrl);
			row.setAppName(app);
			row.setRemark(note);
			row.sanitizeForUpdate();
			applySync(row);
			serverMapper.update(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.getId());
		body.put("synced", Integer.valueOf(1).equals(row.getSyncStatus()));
		body.put("message", row.getSyncMsg());
		return body;
	}

	@Transactional(rollbackFor = Exception.class)
	public List<SysJobServer> remove(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的任务服务");
		}
		List<SysJobServer> rows = new ArrayList<>();
		for (Long id : ids) {
			SysJobServer row = serverMapper.selectOneById(id);
			if (row == null) {
				throw new ServiceException("这套任务服务不存在，或属于其他租户。请刷新列表后再试");
			}
			rows.add(row);
		}
		for (SysJobServer row : rows) {
			serverMapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> sync(Long id) {
		List<SysJobServer> rows = new ArrayList<>();
		if (id == null) {
			rows.addAll(listAll());
			if (rows.isEmpty()) {
				throw new ServiceException("还没有任务服务。请先登记名称、地址和应用后再同步");
			}
		} else {
			rows.add(require(id));
		}
		int ok = 0;
		int fail = 0;
		for (SysJobServer row : rows) {
			applySync(row);
			row.sanitizeForUpdate();
			serverMapper.update(row);
			if (Integer.valueOf(1).equals(row.getSyncStatus())) {
				ok++;
			} else {
				fail++;
			}
		}
		String message = fail == 0
			? "已同步 " + ok + " 套任务服务"
			: "已同步 " + ok + " 套，" + fail + " 套没连上。请查看列表里的同步说明，确认地址后再试";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", ok);
		body.put("fail", fail);
		body.put("message", message);
		return body;
	}

	public static Map<String, Object> view(SysJobServer row) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", row.getId());
		map.put("serverName", row.getServerName());
		map.put("serverUrl", row.getServerUrl());
		map.put("appName", row.getAppName());
		map.put("remark", row.getRemark());
		map.put("passwordSet", row.getAppPassword() != null && !row.getAppPassword().isBlank());
		map.put("syncStatus", row.getSyncStatus());
		map.put("syncMsg", row.getSyncMsg());
		map.put("syncTime", row.getSyncTime());
		map.put("createTime", row.getCreateTime());
		return map;
	}

	private void applySync(SysJobServer row) {
		String message = pushApp(row.getServerUrl(), row.getAppName(), row.getAppPassword());
		row.setSyncTime(LocalDateTime.now());
		if (SYNCED.equals(message)) {
			row.setSyncStatus(1);
			row.setSyncMsg(message);
		} else {
			row.setSyncStatus(0);
			row.setSyncMsg(message);
		}
	}

	private String pushApp(String url, String appName, String password) {
		try {
			String json = "{\"appName\":\"" + jsonEscape(appName) + "\",\"password\":\"" + jsonEscape(password) + "\"}";
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url + "/appInfo/save"))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			String body = response.body() == null ? "" : response.body();
			String compact = body.replaceAll("\\s", "");
			if (response.statusCode() >= 200 && response.statusCode() < 300 && compact.contains("\"success\":true")) {
				return SYNCED;
			}
			log.warn("任务服务登记未接受 status={}", response.statusCode());
			return REJECTED;
		} catch (Exception e) {
			log.warn("任务服务登记连不上 {}", e.getClass().getSimpleName());
			return UNREACHABLE;
		}
	}

	private static String normalizeUrl(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException("请填写服务地址。例如 http://127.0.0.1:7700");
		}
		String url = raw.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		if (url.length() > 200 || !URL.matcher(url).matches()) {
			throw new ServiceException("地址需要以 http:// 或 https:// 开头，只包含主机和端口。例如 http://127.0.0.1:7700");
		}
		int colon = url.lastIndexOf(':');
		int scheme = url.indexOf("://");
		if (colon > scheme + 2) {
			int port = Integer.parseInt(url.substring(colon + 1));
			if (port < 1 || port > 65535) {
				throw new ServiceException("端口需要在 1 到 65535 之间。例如 http://127.0.0.1:7700");
			}
		}
		return url;
	}

	private static String normalizeApp(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException("请填写应用名称。它要和任务服务里的应用一致");
		}
		String app = raw.trim();
		if (!APP.matcher(app).matches()) {
			throw new ServiceException("应用名称请使用字母开头，只含字母、数字、下划线或中划线");
		}
		return app;
	}

	private static String requireText(String raw, String emptyMessage, int max, String tooLong) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException(emptyMessage);
		}
		String text = raw.trim();
		if (text.length() > max) {
			throw new ServiceException(tooLong);
		}
		return text;
	}

	static String openApiAddress(String url) {
		String address = url;
		if (address.startsWith("https://")) {
			address = address.substring("https://".length());
		} else if (address.startsWith("http://")) {
			address = address.substring("http://".length());
		}
		return address;
	}

	private static String jsonEscape(String raw) {
		return raw.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
