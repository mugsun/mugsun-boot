package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysApiKey;
import com.mugsun.boot.system.mapper.SysApiKeyMapper;
import com.mugsun.boot.system.service.ApiKeyCallRecorder;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 密钥管理：生成 AK/SK、启停、删除、校验。
 */
@RestController
@RequestMapping("/system/api-key")
@SaCheckLogin
public class SysApiKeyController {

	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final AntPathMatcher PATHS = new AntPathMatcher();

	private final SysApiKeyMapper apiKeyMapper;
	private final ApiKeyCallRecorder callRecorder;

	public SysApiKeyController(SysApiKeyMapper apiKeyMapper, ApiKeyCallRecorder callRecorder) {
		this.apiKeyMapper = apiKeyMapper;
		this.callRecorder = callRecorder;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:api-key:list")
	public R<Page<SysApiKey>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "10") long pageSize,
								   @RequestParam(required = false) String name,
								   @RequestParam(required = false) String accessKey) {
		QueryWrapper query = QueryWrapper.create();
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		if (accessKey != null && !accessKey.isBlank()) {
			query.like("access_key", accessKey.trim());
		}
		query.orderBy("id", false);
		Page<SysApiKey> page = apiKeyMapper.paginate(pageNum, pageSize, query);
		page.getRecords().forEach(this::maskSecret);
		return R.data(page);
	}

	/** 生成密钥：随机 AK/SK，返回含明文 SK 的记录（仅此一次） */
	@PostMapping("/generate")
	@SaCheckPermission("sys:api-key:generate")
	@RepeatSubmit
	public R<SysApiKey> generate(@RequestBody SysApiKey param) {
		if (param == null) {
			throw new ServiceException("请填写密钥名称");
		}
		normalize(param, true);
		SysApiKey taken = apiKeyMapper.selectOneByQuery(QueryWrapper.create().eq("name", param.getName()));
		if (taken != null) {
			throw new ServiceException("已经有叫「" + param.getName() + "」的密钥。请换一个名称，或编辑原来那个");
		}
		SysApiKey key = new SysApiKey();
		key.setTenantId(TenantContext.current());
		key.setName(param.getName());
		key.setScope(param.getScope());
		key.setRemark(param.getRemark());
		key.setExpireTime(param.getExpireTime());
		key.setApiPath(param.getApiPath());
		key.setStatus(1);
		key.setAccessKey("AK" + randomStr(16));
		key.setSecretKey(randomStr(32));
		apiKeyMapper.insertSelective(key);
		R<SysApiKey> result = R.data(key);
		result.setMsg("已生成。请立即保存 SecretKey，关闭后列表里只会显示掩码");
		return result;
	}

	@PostMapping("/enable/{id}")
	@SaCheckPermission("sys:api-key:edit")
	@RepeatSubmit
	public R<Void> enable(@PathVariable Long id) {
		SysApiKey key = requireKey(id, "启用");
		key.setStatus(1);
		apiKeyMapper.update(key);
		return R.success("已启用。这把密钥可以继续通过校验");
	}

	@PostMapping("/disable/{id}")
	@SaCheckPermission("sys:api-key:edit")
	@RepeatSubmit
	public R<Void> disable(@PathVariable Long id) {
		SysApiKey key = requireKey(id, "停用");
		key.setStatus(0);
		apiKeyMapper.update(key);
		return R.success("已停用。这把密钥不能再通过校验，请先启用再试");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:api-key:remove")
	@RepeatSubmit
	public R<Void> removeBatch(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的密钥");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有密钥不存在。请刷新列表后再删除");
		}
		List<SysApiKey> keys = apiKeyMapper.selectListByIds(ids);
		if (keys.size() != ids.size()) {
			throw new ServiceException("有密钥不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysApiKey key : keys) {
			apiKeyMapper.deleteById(key.getId());
		}
		return R.success("已删除 " + keys.size() + " 个密钥。它们不能再通过校验");
	}

	@PostMapping("/remove/{id}")
	@SaCheckPermission("sys:api-key:remove")
	@RepeatSubmit
	public R<Void> remove(@PathVariable Long id) {
		SysApiKey key = requireKey(id, "删除");
		apiKeyMapper.deleteById(id);
		return R.success("已删除「" + key.getName() + "」。列表里不会再出现，旧密钥也不能再通过校验");
	}

	/** 校验密钥：AK+SK 匹配且启用则通过，并留下一条不含密钥明文的调用记录 */
	@PostMapping("/verify")
	public R<Map<String, Object>> verify(@RequestBody(required = false) VerifyParam param, HttpServletRequest request) {
		long start = System.nanoTime();
		String accessKey = param == null || param.accessKey() == null ? "" : param.accessKey().trim();
		String secretKey = param == null || param.secretKey() == null ? "" : param.secretKey();
		if (accessKey.isEmpty()) {
			throw new ServiceException("请填写 AccessKey");
		}
		if (secretKey.isBlank()) {
			throw new ServiceException("请填写 SecretKey。请使用生成密钥时保存的值，不要使用列表里打码后的内容");
		}
		SysApiKey key;
		if (TenantContext.isPlatformSuperAdmin()) {
			key = TenantContext.ignore(() -> apiKeyMapper.selectOneByQuery(
				QueryWrapper.create().eq("access_key", accessKey)));
		} else {
			QueryWrapper query = QueryWrapper.create().eq("access_key", accessKey);
			String tenant = TenantContext.current();
			if (tenant != null && !tenant.isBlank()) {
				query.eq("tenant_id", tenant);
			}
			key = apiKeyMapper.selectOneByQuery(query);
		}
		boolean enabled = key != null && Integer.valueOf(1).equals(key.getStatus());
		boolean expired = key != null && key.getExpireTime() != null && !key.getExpireTime().isAfter(LocalDateTime.now());
		boolean pathOk = key == null || pathAllowed(key.getApiPath(), param == null ? null : param.path());
		boolean secretOk = enabled && !expired && pathOk && secretKey.equals(key.getSecretKey());
		String message;
		if (key == null) {
			message = "没有找到这个 AccessKey。请到密钥管理核对标识后再试";
		} else if (!enabled) {
			message = "这个密钥已停用。请让管理员启用后再调用";
		} else if (expired) {
			message = "这把密钥已过期。请到密钥管理延长到期时间，或重新生成一把";
		} else if (!pathOk) {
			message = param == null || param.path() == null || param.path().isBlank()
				? "这把密钥限制了可调用路径。请在校验时带上要访问的路径"
				: "这个路径不在密钥允许的范围内。请改用允许的路径，或到密钥管理放宽范围";
		} else if (!secretOk) {
			message = "SecretKey 不正确。请使用生成时保存的密钥，不要使用列表里打码后的值";
		} else {
			message = "校验通过";
		}
		boolean valid = secretOk;
		int costMs = (int) ((System.nanoTime() - start) / 1_000_000L);
		callRecorder.record(key, accessKey, true, request, valid, message, costMs);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("valid", valid);
		result.put("scope", valid && key.getScope() != null ? key.getScope() : "");
		result.put("message", message);
		R<Map<String, Object>> body = R.data(result);
		body.setMsg(message);
		if (!valid) {
			body.setSuccess(false);
			body.setCode(ResultCode.FAILURE.getCode());
		}
		return body;
	}

	private SysApiKey requireKey(Long id, String action) {
		if (id == null) {
			throw new ServiceException("这个密钥不存在，或属于其他租户。请刷新列表后再" + action);
		}
		SysApiKey key = apiKeyMapper.selectOneById(id);
		if (key == null) {
			throw new ServiceException("这个密钥不存在，或属于其他租户。请刷新列表后再" + action);
		}
		return key;
	}

	private void normalize(SysApiKey param, boolean creating) {
		String name = param.getName() == null ? "" : param.getName().trim();
		if (creating && name.isEmpty()) {
			throw new ServiceException("请填写密钥名称");
		}
		if (!name.isEmpty() && name.length() > 64) {
			throw new ServiceException("密钥名称请控制在 64 个字以内");
		}
		param.setName(name);
		if (param.getScope() != null && !param.getScope().isBlank()) {
			String scope = param.getScope().trim();
			if (scope.length() > 255) {
				throw new ServiceException("作用域请控制在 255 个字以内");
			}
			for (String part : scope.split(",")) {
				String item = part.trim();
				if (item.isEmpty() || !item.matches("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$")) {
					throw new ServiceException("作用域请用字母开头，多项用英文逗号分隔，例如 read,write");
				}
			}
			param.setScope(scope);
		} else {
			param.setScope(null);
		}
		if (param.getRemark() != null && param.getRemark().length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if (param.getExpireTime() != null && !param.getExpireTime().isAfter(LocalDateTime.now())) {
			throw new ServiceException("到期时间要晚于现在。不限制有效期可以留空");
		}
		param.setApiPath(normalizePath(param.getApiPath()));
	}

	private String normalizePath(String raw) {
		if (raw == null || raw.isBlank() || "/**".equals(raw.trim())) {
			return null;
		}
		String[] parts = raw.split(",");
		StringBuilder joined = new StringBuilder();
		for (String part : parts) {
			String path = part.trim();
			if (path.isEmpty()) {
				continue;
			}
			if (path.length() > 255 || path.chars().anyMatch(Character::isWhitespace) || !path.startsWith("/")) {
				throw new ServiceException("可调用路径要以 / 开头，多项用英文逗号分隔。不限制路径可以留空");
			}
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append(path);
		}
		if (joined.length() > 255) {
			throw new ServiceException("可调用路径请控制在 255 个字以内");
		}
		return joined.isEmpty() ? null : joined.toString();
	}

	private boolean pathAllowed(String apiPath, String requestPath) {
		if (apiPath == null || apiPath.isBlank() || "/**".equals(apiPath.trim())) {
			return true;
		}
		if (requestPath == null || requestPath.isBlank()) {
			return false;
		}
		String path = requestPath.trim();
		for (String allowed : apiPath.split(",")) {
			String candidate = allowed.trim();
			if (!candidate.isEmpty() && PATHS.match(candidate, path)) {
				return true;
			}
		}
		return false;
	}

	private void maskSecret(SysApiKey key) {
		String sk = key.getSecretKey();
		if (sk == null || sk.length() <= 6) {
			key.setSecretKey("******");
			return;
		}
		key.setSecretKey(sk.substring(0, 6) + "******");
	}

	private String randomStr(int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
		}
		return sb.toString();
	}

	/** 校验参数 */
	public record VerifyParam(String accessKey, String secretKey, String path) {
	}
}
