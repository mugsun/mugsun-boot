package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.ResultCode;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 附件越权裁决（B4）：所有校验先带租户过滤，跨租户/不存在/无权一律 404（不暴露存在性）。
 * <ul>
 *   <li>下载：上传者本人 / 持 {@link #PERM_MANAGE} / 文件已公开时本租户任意登录用户</li>
 *   <li>删除：仅上传者本人或持 {@link #PERM_MANAGE}</li>
 *   <li>上传：普通用户强制 private；仅持 {@link #PERM_PUBLIC} 可设公开</li>
 * </ul>
 * 权限判定以 {@code Predicate<String>} 注入（控制器传 {@code StpUtil::hasPermission}），便于单测。
 */
@Service
public class FileAccessService {

	public static final String PERM_MANAGE = "sys:file:manage";
	public static final String PERM_PUBLIC = "sys:file:public";
	public static final String PERM_REMOVE = "sys:file:remove";

	private final SysAttachMapper attachMapper;

	public FileAccessService(SysAttachMapper attachMapper) {
		this.attachMapper = attachMapper;
	}

	/** 按 id 加载可下载附件；不可下载一律抛 404 */
	public SysAttach requireDownloadable(Long id, String tenantId, Long userId, Predicate<String> hasPerm) {
		SysAttach attach = loadInTenant(id, tenantId);
		if (attach == null || !canDownload(attach, userId, hasPerm)) {
			throw notFound();
		}
		return attach;
	}

	/** 按 id 加载可删除附件；不存在/跨租户/无权一律返回 null（调用方按“不存在”处理，不区分原因） */
	public SysAttach findRemovable(Long id, String tenantId, Long userId, Predicate<String> hasPerm) {
		SysAttach attach = loadInTenant(id, tenantId);
		return attach != null && canRemove(attach, userId, hasPerm) ? attach : null;
	}

	public boolean canDownload(SysAttach attach, Long userId, Predicate<String> hasPerm) {
		return OssConstants.ACCESS_PUBLIC.equals(attach.getAccess()) || isOwner(attach, userId)
			|| hasPerm.test(PERM_MANAGE);
	}

	public boolean canRemove(SysAttach attach, Long userId, Predicate<String> hasPerm) {
		return isOwner(attach, userId) || hasPerm.test(PERM_MANAGE);
	}

	/** 上传访问级别裁决：请求 public 但无 {@link #PERM_PUBLIC} 时降为 private */
	public boolean resolvePublic(String requestedAccess, Predicate<String> hasPerm) {
		return OssConstants.ACCESS_PUBLIC.equals(requestedAccess) && hasPerm.test(PERM_PUBLIC);
	}

	private boolean isOwner(SysAttach attach, Long userId) {
		return userId != null && attach.getCreateUser() != null && Objects.equals(attach.getCreateUser(), userId);
	}

	/** 显式租户过滤（不只依赖 Flex 自动租户条件）；租户为空/ALL/IGNORE 时交由 Flex 默认行为 */
	private SysAttach loadInTenant(Long id, String tenantId) {
		if (id == null) {
			return null;
		}
		QueryWrapper query = QueryWrapper.create().eq("id", id);
		if (tenantId != null && !TenantContext.ALL.equals(tenantId) && !TenantContext.IGNORE.equals(tenantId)) {
			query.eq("tenant_id", tenantId);
		}
		SysAttach attach = attachMapper.selectOneByQuery(query);
		if (attach != null && tenantId != null && !TenantContext.ALL.equals(tenantId)
			&& !TenantContext.IGNORE.equals(tenantId) && !tenantId.equals(attach.getTenantId())) {
			return null;
		}
		return attach;
	}

	private static ServiceException notFound() {
		return new ServiceException(ResultCode.NOT_FOUND);
	}
}
