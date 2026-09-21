package com.mugsun.boot.oauth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.oauth.entity.SysOauthLog;
import com.mugsun.boot.oauth.mapper.SysOauthLogMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

/**
 * 开放接口调用日志查询（平台级留痕，不做租户过滤）。
 */
@RestController
@RequestMapping("/system/oauth-log")
@SaCheckLogin
public class OAuthLogController {

	private final SysOauthLogMapper logMapper;

	public OAuthLogController(SysOauthLogMapper logMapper) {
		this.logMapper = logMapper;
	}

	@GetMapping("/page")
	public R<Page<SysOauthLog>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) Integer status,
									 @RequestParam(required = false) String clientId) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (status != null) {
			query.and("status = ?", status);
		}
		if (clientId != null && !clientId.isBlank()) {
			query.and("client_id = ?", clientId);
		}
		Page<SysOauthLog> page = TenantContext.ignore(() ->
			logMapper.paginate(pageNum, pageSize, query));
		return R.data(page);
	}
}
