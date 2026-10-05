package com.mugsun.boot.notify.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.json.JSONUtil;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.notify.entity.SysNotifyChannel;
import com.mugsun.boot.notify.mapper.SysNotifyChannelMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 通知渠道配置：站内信、邮件、短信。凭据留空表示不改，列表不回明文。
 */
@RestController
@RequestMapping("/system/notify/channel")
@SaCheckLogin
public class SysNotifyChannelController {

	private static final Set<String> BUILTIN = Set.of(
		NotifyConstants.CHANNEL_IN_APP, NotifyConstants.CHANNEL_MAIL, NotifyConstants.CHANNEL_SMS);

	private final SysNotifyChannelMapper channelMapper;

	public SysNotifyChannelController(SysNotifyChannelMapper channelMapper) {
		this.channelMapper = channelMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:notify-channel:list")
	public R<Page<SysNotifyChannel>> page(@RequestParam(defaultValue = "1") long pageNum,
										  @RequestParam(defaultValue = "10") long pageSize,
										  @RequestParam(required = false) String name) {
		assertPage(pageNum, pageSize);
		QueryWrapper query = QueryWrapper.create();
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		query.orderBy("id", false);
		Page<SysNotifyChannel> page = channelMapper.paginate(pageNum, pageSize, query);
		page.getRecords().forEach(channel -> channel.setSecret(null));
		return R.data(page);
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:notify-channel:list")
	public R<SysNotifyChannel> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条渠道后再查看");
		}
		SysNotifyChannel channel = channelMapper.selectOneById(id);
		if (channel == null) {
			throw new ServiceException("这个渠道不存在。请刷新列表后再看");
		}
		channel.setSecret(null);
		return R.data(channel);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:notify-channel:save")
	@RepeatSubmit
	public R<Void> submit(@RequestBody SysNotifyChannel channel) {
		if (channel == null) {
			throw new ServiceException("请选择渠道");
		}
		normalize(channel);
		if (channel.getId() == null) {
			SysNotifyChannel taken = channelMapper.selectOneByQuery(
				QueryWrapper.create().eq("channel", channel.getChannel()));
			if (taken != null) {
				throw new ServiceException("渠道「" + taken.getName() + "」已经有了。请直接编辑原来那条，不要再新增");
			}
			if (channel.getSecret() != null && channel.getSecret().isBlank()) {
				channel.setSecret(null);
			}
			channelMapper.insertSelective(channel);
			return R.success("已保存。列表里可以查看这个渠道是否启用");
		}
		SysNotifyChannel before = channelMapper.selectOneById(channel.getId());
		if (before == null) {
			throw new ServiceException("这个渠道不存在。请刷新列表后再编辑");
		}
		if (before.getChannel() != null && !before.getChannel().equals(channel.getChannel())) {
			throw new ServiceException("渠道编码创建后不能修改。请编辑原来那条");
		}
		channel.setChannel(before.getChannel());
		if (channel.getSecret() == null || channel.getSecret().isBlank()) {
			channel.setSecret(null);
		}
		channelMapper.update(channel);
		return R.success("已保存。列表里可以查看这个渠道是否启用");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:notify-channel:remove")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		List<SysNotifyChannel> rows = loadExisting(ids, "渠道");
		for (SysNotifyChannel row : rows) {
			if (BUILTIN.contains(row.getChannel())) {
				throw new ServiceException("「" + row.getName() + "」是内置通知渠道，不能删除。不需要时可以停用邮件或短信");
			}
		}
		for (SysNotifyChannel row : rows) {
			channelMapper.deleteById(row.getId());
		}
		return R.success("已删除 " + rows.size() + " 条渠道。列表里不会再出现");
	}

	private void normalize(SysNotifyChannel channel) {
		String code = channel.getChannel() == null ? "" : channel.getChannel().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请选择渠道：站内信、邮件或短信");
		}
		if (!BUILTIN.contains(code)) {
			throw new ServiceException("渠道请选择站内信、邮件或短信。这三项已经内置，直接编辑即可");
		}
		channel.setChannel(code);
		String name = channel.getName() == null ? "" : channel.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写渠道名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("渠道名称请控制在 64 个字以内");
		}
		channel.setName(name);
		if (channel.getStatus() == null || (channel.getStatus() != 0 && channel.getStatus() != 1)) {
			throw new ServiceException("状态只能选择启用或停用");
		}
		if (NotifyConstants.CHANNEL_IN_APP.equals(code) && channel.getStatus() == 0) {
			throw new ServiceException("站内信是默认通知渠道，不能停用。欢迎通知还要用它");
		}
		String config = channel.getConfig() == null ? "" : channel.getConfig().trim();
		if (config.length() > 4000) {
			throw new ServiceException("渠道配置请控制在 4000 个字符以内");
		}
		if (!config.isEmpty()) {
			if (!config.startsWith("{")) {
				throw new ServiceException("渠道配置请写成 JSON，例如 {\"host\":\"smtp.example.com\",\"port\":465}。请检查括号和引号后再保存");
			}
			try {
				JSONUtil.parseObj(config);
			} catch (ServiceException ex) {
				throw ex;
			} catch (RuntimeException ex) {
				throw new ServiceException("渠道配置请写成 JSON，例如 {\"host\":\"smtp.example.com\",\"port\":465}。请检查括号和引号后再保存");
			}
		}
		channel.setConfig(config.isEmpty() ? "{}" : config);
		String remark = channel.getRemark() == null ? "" : channel.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		channel.setRemark(remark.isEmpty() ? null : remark);
	}

	private void assertPage(long pageNum, long pageSize) {
		if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
			throw new ServiceException("页码从 1 开始，每页最多 100 条");
		}
	}

	private List<SysNotifyChannel> loadExisting(List<Long> ids, String label) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的" + label);
		}
		List<Long> distinct = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有" + label + "不存在。请刷新列表后再删除");
			}
			if (!distinct.contains(id)) {
				distinct.add(id);
			}
		}
		List<SysNotifyChannel> rows = channelMapper.selectListByIds(distinct);
		if (rows.size() != distinct.size()) {
			throw new ServiceException("有" + label + "不存在，或已经被删除。请刷新列表后再试");
		}
		return rows;
	}
}
