package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.app.dto.AppUserVO;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * 手机端「我是谁」：当前登录用户的编号、账号和显示名（没填昵称的显示账号）。
 *
 * <p>登录那组接口（{@link AppAuthController} 的 {@code /app/auth/me}）和首页（{@link AppHomeService}）都要这三样。
 * 单独成一个类，是因为微服务版里登录接口在登录服务、首页在 system 服务，不在一个进程里：
 * 这个类只读用户表，两边都能带；写在 {@link AppHomeService} 里的话，登录服务要连首页用的待办、站内信、公告一起带上。
 */
@Service
public class AppUserReader {

	private final SysUserMapper userMapper;

	public AppUserReader(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	public AppUserVO me() {
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(StpUtil.getLoginIdAsLong()));
		String nick = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		return new AppUserVO(user.getId(), user.getUsername(), nick);
	}
}
