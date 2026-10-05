package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysOss;
import com.mugsun.boot.system.mapper.SysOssMapper;
import com.mugsun.boot.system.service.OssService;
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
	private final OssService ossService;

	public SysOssController(SysOssMapper ossMapper, OssService ossService) {
		this.ossMapper = ossMapper;
		this.ossService = ossService;
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
	public R<SysOss> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表里选择一套存储后再查看");
		}
		SysOss oss = ossMapper.selectOneById(id);
		if (oss == null) {
			throw new ServiceException("这套存储不存在，或已被删除。请刷新列表后再看");
		}
		oss.setSecretKey(null);
		return R.data(oss);
	}

	/** 新增/更新：secretKey 留空表示不修改凭据（编辑回显本就拿不到明文） */
	@SaCheckPermission("sys:oss:save")
	@PostMapping("/submit")
	@RepeatSubmit(message = "请勿重复保存。请到列表里确认这套存储是否已经提交")
	public R<Void> submit(@RequestBody SysOss oss) {
		if (oss == null) {
			throw new ServiceException("请填写存储名称和标识后再保存");
		}
		String name = requireText(oss.getName(), "请填写存储名称", "存储名称请控制在 64 字以内", 64);
		String code = requireCode(oss.getOssCode(), "请填写存储标识。用字母开头，只能包含字母、数字、下划线或连字符");
		if (oss.getCategory() == null || !CATEGORIES.contains(oss.getCategory())) {
			throw new ServiceException("请选择本地存储、MinIO 或阿里云");
		}
		if (oss.getSecretKey() != null && oss.getSecretKey().isBlank()) {
			oss.setSecretKey(null);
		}
		oss.setName(name);
		oss.setOssCode(code);
		SysOss sameCode = ossMapper.selectOneByQuery(QueryWrapper.create().eq("oss_code", code));
		if (oss.getId() == null) {
			if (sameCode != null) {
				throw new ServiceException("存储标识已存在。请换一个标识，或编辑原来那一套");
			}
			if (oss.getStatus() == null) {
				oss.setStatus(OssConstants.STATUS_DISABLE);
			}
			ossMapper.insertSelective(oss);
			return R.success("已保存。可以点检查，确认目录或地址可用后再启用");
		}
		if (sameCode != null && !oss.getId().equals(sameCode.getId())) {
			throw new ServiceException("存储标识已存在。请换一个标识，或编辑原来那一套");
		}
		SysOss exist = ossMapper.selectOneById(oss.getId());
		if (exist == null) {
			throw new ServiceException("这套存储不存在，或已被删除。请刷新列表后再编辑");
		}
		ossMapper.update(oss);
		return R.success("已保存。若改了正在使用的配置，下次上传会按新配置存储");
	}

	@SaCheckPermission("sys:oss:remove")
	@PostMapping("/remove")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这套存储是否还在")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请从列表里选择要删除的存储");
		}
		int removed = 0;
		for (Long id : ids) {
			if (id != null && ossMapper.selectOneById(id) != null) {
				ossMapper.deleteById(id);
				removed++;
			}
		}
		if (removed == 0) {
			throw new ServiceException("这些存储不存在，或已被删除。请刷新列表后再试");
		}
		return R.success("已删除。列表里不会再出现这套存储");
	}

	/** 启用指定配置：同租户互斥，其余置禁用 */
	@SaCheckPermission("sys:oss:edit")
	@PostMapping("/enable/{id}")
	@RepeatSubmit(message = "请勿重复启用。请看列表里的状态是否已经是启用")
	public R<Void> enable(@PathVariable Long id) {
		SysOss target = ossMapper.selectOneById(id);
		if (target == null) {
			throw new ServiceException("这套存储不存在，或已被删除。请刷新列表后再启用");
		}
		if (target.getStatus() != null && target.getStatus() == OssConstants.STATUS_ENABLE) {
			return R.success("这套存储已经在用。新上传的文件会存到这里");
		}
		ossMapper.selectListByQuery(QueryWrapper.create().eq("status", OssConstants.STATUS_ENABLE)).forEach(o -> {
			o.setStatus(OssConstants.STATUS_DISABLE);
			ossMapper.update(o);
		});
		target.setStatus(OssConstants.STATUS_ENABLE);
		ossMapper.update(target);
		return R.success("已启用。新上传的文件会改存到这套存储");
	}

	/** 检查这一套配置：本地试写，远程确认地址能连上。 */
	@SaCheckPermission("sys:oss:edit")
	@PostMapping("/probe/{id}")
	@RepeatSubmit(message = "请勿重复检查。上一次检查的结果还在提示里")
	public R<String> probe(@PathVariable Long id) {
		SysOss target = ossMapper.selectOneById(id);
		if (target == null) {
			throw new ServiceException("这套存储不存在，或已被删除。请刷新列表后再检查");
		}
		String message = ossService.probe(target);
		R<String> result = R.data(message);
		result.setMsg(message);
		return result;
	}

	private static String requireText(String raw, String empty, String tooLong, int max) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException(empty);
		}
		String text = raw.trim();
		if (text.length() > max) {
			throw new ServiceException(tooLong);
		}
		return text;
	}

	private static String requireCode(String raw, String message) {
		if (raw == null || raw.isBlank() || !raw.trim().matches("^[A-Za-z][A-Za-z0-9_-]{0,31}$")) {
			throw new ServiceException(message);
		}
		return raw.trim();
	}
}
