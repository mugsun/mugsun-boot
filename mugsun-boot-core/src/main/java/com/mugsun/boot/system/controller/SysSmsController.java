package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysSms;
import com.mugsun.boot.system.mapper.SysSmsMapper;
import com.mugsun.boot.system.service.SmsService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 短信平台配置管理
 */
@RestController
@RequestMapping("/system/sms")
@SaCheckLogin
public class SysSmsController {

	private static final Set<String> CATEGORIES = Set.of("alibaba", "tencent");

	private final SysSmsMapper smsMapper;
	private final SmsService smsService;

	public SysSmsController(SysSmsMapper smsMapper, SmsService smsService) {
		this.smsMapper = smsMapper;
		this.smsService = smsService;
	}

	@GetMapping("/page")
	public R<Page<SysSms>> page(@RequestParam(defaultValue = "1") long pageNum,
								@RequestParam(defaultValue = "10") long pageSize) {
		Page<SysSms> page = smsMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
		page.getRecords().forEach(row -> row.setSecretKey(null));
		return R.data(page);
	}

	@GetMapping("/detail")
	public R<SysSms> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表里选择一套短信配置后再查看");
		}
		SysSms sms = smsMapper.selectOneById(id);
		if (sms == null) {
			throw new ServiceException("这套短信配置不存在，或已被删除。请刷新列表后再看");
		}
		sms.setSecretKey(null);
		return R.data(sms);
	}

	@SaCheckPermission("sys:sms:save")
	@PostMapping("/submit")
	@RepeatSubmit(message = "请勿重复保存。请到列表里确认这套短信配置是否已经提交")
	public R<Void> submit(@RequestBody SysSms sms) {
		if (sms == null) {
			throw new ServiceException("请填写短信名称和标识后再保存");
		}
		sms.setName(requireText(sms.getName(), "请填写短信名称", "短信名称请控制在 64 字以内", 64));
		sms.setSmsCode(requireCode(sms.getSmsCode()));
		if (sms.getCategory() == null || !CATEGORIES.contains(sms.getCategory())) {
			throw new ServiceException("请选择阿里云或腾讯云");
		}
		sms.setSignature(requireText(sms.getSignature(), "请填写短信签名", "短信签名请控制在 32 字以内", 32));
		sms.setTemplateId(requireText(sms.getTemplateId(), "请填写模板编号", "模板编号请控制在 64 字以内", 64));
		if (sms.getSecretKey() != null && sms.getSecretKey().isBlank()) {
			sms.setSecretKey(null);
		}
		if (sms.getAccessKey() != null && sms.getAccessKey().isBlank()) {
			sms.setAccessKey(null);
		}
		SysSms sameCode = smsMapper.selectOneByQuery(QueryWrapper.create().eq("sms_code", sms.getSmsCode()));
		if (sms.getId() == null) {
			if (sameCode != null) {
				throw new ServiceException("配置标识已存在。请换一个标识，或编辑原来那一套");
			}
			if (sms.getAccessKey() == null || sms.getSecretKey() == null) {
				throw new ServiceException("请填写 AccessKey 和 SecretKey。密钥只在保存时提交，列表里不会再显示");
			}
			if (sms.getStatus() == null) {
				sms.setStatus(0);
			}
			smsMapper.insertSelective(sms);
			return R.success("已保存。可以点试发，用一个手机号确认这套配置能不能发出去");
		}
		if (sameCode != null && !sms.getId().equals(sameCode.getId())) {
			throw new ServiceException("配置标识已存在。请换一个标识，或编辑原来那一套");
		}
		if (smsMapper.selectOneById(sms.getId()) == null) {
			throw new ServiceException("这套短信配置不存在，或已被删除。请刷新列表后再编辑");
		}
		smsMapper.update(sms);
		return R.success("已保存。密钥留空表示继续用原来的");
	}

	@SaCheckPermission("sys:sms:remove")
	@PostMapping("/remove")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这套配置是否还在")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请从列表里选择要删除的短信配置");
		}
		int removed = 0;
		for (Long id : ids) {
			if (id != null && smsMapper.selectOneById(id) != null) {
				smsMapper.deleteById(id);
				removed++;
			}
		}
		if (removed == 0) {
			throw new ServiceException("这些短信配置不存在，或已被删除。请刷新列表后再试");
		}
		return R.success("已删除。列表里不会再出现这套配置");
	}

	/** 启用指定配置：同租户互斥，其余置禁用 */
	@SaCheckPermission("sys:sms:edit")
	@PostMapping("/enable/{id}")
	@RepeatSubmit(message = "请勿重复启用。请看列表里的状态是否已经是启用")
	public R<Void> enable(@PathVariable Long id) {
		SysSms target = smsMapper.selectOneById(id);
		if (target == null) {
			throw new ServiceException("这套短信配置不存在，或已被删除。请刷新列表后再启用");
		}
		if (target.getStatus() != null && target.getStatus() == 1) {
			return R.success("这套短信配置已经在用。验证码和通知会从这里发出");
		}
		smsMapper.selectListByQuery(QueryWrapper.create().eq("status", 1)).forEach(s -> {
			s.setStatus(0);
			smsMapper.update(s);
		});
		target.setStatus(1);
		smsMapper.update(target);
		return R.success("已启用。验证码和通知会改从这套配置发出");
	}

	/** 按这一套配置向指定手机试发，不写入登录验证码。 */
	@SaCheckPermission("sys:sms:edit")
	@PostMapping("/debug")
	@RepeatSubmit(message = "请勿重复试发。请先看手机有没有收到上一条")
	public R<String> debug(@RequestBody SmsDebug body) {
		if (body == null || body.id() == null) {
			throw new ServiceException("请从列表里选择一套短信配置后再试发");
		}
		SysSms target = smsMapper.selectOneById(body.id());
		if (target == null) {
			throw new ServiceException("这套短信配置不存在，或已被删除。请刷新列表后再试发");
		}
		String message = smsService.debugSend(target, body.phone());
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

	private static String requireCode(String raw) {
		if (raw == null || raw.isBlank() || !raw.trim().matches("^[A-Za-z][A-Za-z0-9_-]{0,31}$")) {
			throw new ServiceException("配置标识请使用字母开头，只能包含字母、数字、下划线或连字符");
		}
		return raw.trim();
	}

	public record SmsDebug(Long id, String phone) {
	}
}
