package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysOss;
import com.mugsun.boot.system.mapper.SysOssMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 对象存储配置管理：secretKey 凭据不出管理端（page/detail 置空，submit 留空表示不修改）；
 * category 固定键保存期校验，未知类别 fail-fast（防启用时才炸）。
 */
@RestController
@RequestMapping("/system/oss")
@SaCheckLogin
public class SysOssController {

	/** category 固定键白名单（禁类名入库：仅允许 OssService 可映射的类别） */
	private static final Set<String> CATEGORIES = Set.of(
		OssConstants.CATEGORY_LOCAL, OssConstants.CATEGORY_MINIO, OssConstants.CATEGORY_ALIYUN);

	private final SysOssMapper ossMapper;

	public SysOssController(SysOssMapper ossMapper) {
		this.ossMapper = ossMapper;
	}

	@GetMapping("/page")
	public R<Page<SysOss>> page(@RequestParam(defaultValue = "1") long pageNum,
								@RequestParam(defaultValue = "10") long pageSize) {
		Page<SysOss> page = ossMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
		// 凭据不出管理端（secretKey 列查询已自动解密，置空防外泄）
		page.getRecords().forEach(o -> o.setSecretKey(null));
		return R.data(page);
	}

	@GetMapping("/detail")
	public R<SysOss> detail(@RequestParam Long id) {
		SysOss oss = ossMapper.selectOneById(id);
		if (oss != null) {
			oss.setSecretKey(null);
		}
		return R.data(oss);
	}

	/** 新增/更新：secretKey 留空表示不修改凭据（编辑回显本就拿不到明文） */
	@SaCheckPermission("sys:oss:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysOss oss) {
		if (oss.getCategory() == null || !CATEGORIES.contains(oss.getCategory())) {
			throw new ServiceException("未知存储类别: " + oss.getCategory());
		}
		if (oss.getSecretKey() != null && oss.getSecretKey().isBlank()) {
			oss.setSecretKey(null);
		}
		if (oss.getId() == null) {
			ossMapper.insertSelective(oss);
		} else {
			ossMapper.update(oss);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:oss:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		ids.forEach(ossMapper::deleteById);
		return R.success("删除成功");
	}

	/** 启用指定配置：同租户互斥，其余置禁用 */
	@SaCheckPermission("sys:oss:edit")
	@PostMapping("/enable/{id}")
	public R<Void> enable(@PathVariable Long id) {
		ossMapper.selectListByQuery(QueryWrapper.create().eq("status", OssConstants.STATUS_ENABLE)).forEach(o -> {
			o.setStatus(OssConstants.STATUS_DISABLE);
			ossMapper.update(o);
		});
		SysOss target = ossMapper.selectOneById(id);
		if (target != null) {
			target.setStatus(OssConstants.STATUS_ENABLE);
			ossMapper.update(target);
		}
		return R.success("已启用");
	}
}
