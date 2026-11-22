package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 头像绑定：把本人上传的公开图片设成头像。只读写数据库，不碰文件存储。
 * <p>和 {@link AvatarUploadService} 分开放：个人中心改头像只用到这一个类，不必为此带上文件存储；
 * 微服务版里文件存储在单独的服务上，登录这边照样能改头像。
 * 头像区的范围、头像对外地址的写法，上传和绑定共用，所以常量和判断也放在这里。
 */
@Service
public class AvatarBinder {

	public static final String PATH_AVATAR = OssConstants.PATH_PUBLIC + "avatar/";
	/** 头像对外 URL 前缀（相对后端根，前端按其它接口同样的 baseURL 拼接） */
	public static final String RAW_PREFIX = "/auth/avatar/raw/";

	private final SysAttachMapper attachMapper;
	private final UserAvatarWriter avatarWriter;

	public AvatarBinder(SysAttachMapper attachMapper, UserAvatarWriter avatarWriter) {
		this.attachMapper = attachMapper;
		this.avatarWriter = avatarWriter;
	}

	/**
	 * update-avatar 绑定：只允许本人上传、本租户、public 的附件；外链/他人/私有/不存在一律拒绝。
	 * 兼容两种入参：头像上传返回的 {@code /auth/avatar/raw/{id}}（可带 /api 或域名前缀），或附件登记 url。
	 * 头像区附件落库为规范 raw 地址，其余本人公开附件落库为其登记 url。
	 */
	@Transactional(rollbackFor = Exception.class)
	public String bind(String avatar, long userId, String tenantId) {
		String value = avatar == null ? "" : avatar.trim();
		if (value.isEmpty() || value.length() > 255) {
			throw invalid();
		}
		QueryWrapper query = QueryWrapper.create();
		Long id = rawIdOf(value);
		if (id != null) {
			query.eq("id", id);
		} else {
			query.eq("url", value);
		}
		boolean scoped = tenantId != null && !TenantContext.ALL.equals(tenantId) && !TenantContext.IGNORE.equals(tenantId);
		if (scoped) {
			query.eq("tenant_id", tenantId);
		}
		SysAttach attach = attachMapper.selectOneByQuery(query);
		if (attach == null || (scoped && !tenantId.equals(attach.getTenantId()))
			|| attach.getCreateUser() == null || attach.getCreateUser() != userId
			|| !OssConstants.ACCESS_PUBLIC.equals(attach.getAccess())) {
			throw invalid();
		}
		String url = isAvatarArea(attach) ? RAW_PREFIX + attach.getId() : attach.getUrl();
		avatarWriter.write(userId, url);
		return url;
	}

	/** 是不是头像区的附件：public 且位于 public/avatar/。匿名输出头像、绑定头像都按这个裁决 */
	public static boolean isAvatarArea(SysAttach attach) {
		return OssConstants.ACCESS_PUBLIC.equals(attach.getAccess()) && attach.getPath() != null
			&& attach.getPath().startsWith(PATH_AVATAR) && !attach.getPath().contains("..");
	}

	static Long rawIdOf(String value) {
		int idx = value.indexOf(RAW_PREFIX);
		if (idx < 0) {
			return null;
		}
		String tail = value.substring(idx + RAW_PREFIX.length());
		if (tail.isEmpty() || tail.length() > 19 || !tail.chars().allMatch(Character::isDigit)) {
			return null;
		}
		return Long.valueOf(tail);
	}

	private static ServiceException invalid() {
		return new ServiceException("头像只能使用你自己上传的公开图片。请重新上传头像后再保存");
	}
}
