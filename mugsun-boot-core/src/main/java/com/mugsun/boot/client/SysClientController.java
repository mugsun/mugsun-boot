package com.mugsun.boot.client;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.client.mapper.SysClientMapper;
import com.mugsun.boot.common.constant.ClientConstants;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录客户端策略管理：CRUD + 启停。sys_client 无租户列，平台级配置。
 */
@RestController
@RequestMapping("/system/client")
@SaCheckLogin
public class SysClientController {

	private static final Set<String> BUILTIN = Set.of(ClientConstants.DEFAULT_CLIENT_ID, ClientConstants.APP_CLIENT_ID);

	private final SysClientMapper clientMapper;

	public SysClientController(SysClientMapper clientMapper) {
		this.clientMapper = clientMapper;
	}

	@GetMapping("/page")
	public R<Page<SysClient>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "10") long pageSize,
								   @RequestParam(required = false) String clientId,
								   @RequestParam(required = false) String clientName) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (clientId != null && !clientId.isBlank()) {
			query.like("client_id", clientId.trim());
		}
		if (clientName != null && !clientName.isBlank()) {
			query.like("client_name", clientName.trim());
		}
		return R.data(clientMapper.paginate(pageNum, pageSize, query));
	}

	@RepeatSubmit
	@SaCheckPermission("sys:client:save")
	@PostMapping("/save")
	public R<Void> save(@RequestBody SysClient client) {
		if (client == null) {
			throw new ServiceException("请填写客户端码和名称后再保存");
		}
		normalize(client);
		if (client.getId() == null) {
			SysClient exist = clientMapper.selectOneByQuery(QueryWrapper.create().eq("client_id", client.getClientId()));
			if (exist != null) {
				throw new ServiceException("客户端码「" + client.getClientId() + "」已存在。请换一个，或编辑原来那个");
			}
			clientMapper.insertSelective(client);
		} else {
			SysClient exist = clientMapper.selectOneById(client.getId());
			if (exist == null) {
				throw new ServiceException("这个客户端不存在。请刷新列表后再编辑");
			}
			if (!exist.getClientId().equals(client.getClientId())) {
				throw new ServiceException("客户端码创建后不能修改。请新增一个客户端，再删除原来的");
			}
			clientMapper.update(client);
		}
		return R.success("已保存。这个客户端登录时会按这里的验证码、在线数和令牌有效期执行");
	}

	@RepeatSubmit
	@SaCheckPermission("sys:client:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		List<SysClient> rows = requireRows(ids);
		for (SysClient row : rows) {
			if (BUILTIN.contains(row.getClientId())) {
				throw new ServiceException("「" + row.getClientName() + "」是内置登录客户端，不能删除。请改其他客户端");
			}
		}
		clientMapper.deleteBatchByIds(rows.stream().map(SysClient::getId).toList());
		return R.success("已删除 " + rows.size() + " 个客户端。列表里不会再出现");
	}

	@SaCheckPermission("sys:client:edit")
	@PostMapping("/enable/{id}")
	public R<Void> enable(@PathVariable Long id) {
		return changeStatus(id, 1);
	}

	@SaCheckPermission("sys:client:edit")
	@PostMapping("/disable/{id}")
	public R<Void> disable(@PathVariable Long id) {
		return changeStatus(id, 0);
	}

	private R<Void> changeStatus(Long id, int status) {
		SysClient exist = clientMapper.selectOneById(id);
		if (exist == null) {
			throw new ServiceException("这个客户端不存在。请刷新列表后再改状态");
		}
		if (status == 0 && BUILTIN.contains(exist.getClientId())) {
			throw new ServiceException("「" + exist.getClientName() + "」是内置登录客户端，不能停用。请改其他客户端");
		}
		UpdateChain.of(SysClient.class).set(SysClient::getStatus, status).where(SysClient::getId).eq(id).update();
		return R.success(status == 1
			? "已启用。这个客户端会按已保存的验证码、在线数和令牌有效期登录"
			: "已停用。这个客户端会改用默认策略：验证码开启、不限在线、令牌 30 天");
	}

	private List<SysClient> requireRows(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的客户端");
		}
		LinkedHashSet<Long> distinct = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有客户端不存在。请刷新列表后再删除");
			}
			distinct.add(id);
		}
		List<Long> idList = new ArrayList<>(distinct);
		List<SysClient> rows = clientMapper.selectListByIds(idList);
		if (rows.size() != idList.size()) {
			throw new ServiceException("有客户端不存在，或已经被删除。请刷新列表后再试");
		}
		return rows;
	}

	private void normalize(SysClient client) {
		String code = client.getClientId() == null ? "" : client.getClientId().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写客户端码");
		}
		if (!code.matches("^[a-z][a-z0-9_-]{0,63}$")) {
			throw new ServiceException("客户端码请用小写字母开头，只能包含小写字母、数字、下划线和中横线");
		}
		client.setClientId(code);
		String name = client.getClientName() == null ? "" : client.getClientName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写客户端名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("客户端名称请控制在 64 个字以内");
		}
		client.setClientName(name);
		if (client.getCaptchaEnabled() == null) {
			client.setCaptchaEnabled(1);
		} else if (client.getCaptchaEnabled() != 0 && client.getCaptchaEnabled() != 1) {
			throw new ServiceException("图形验证码只能选择开启或关闭");
		}
		if (client.getMaxOnline() == null) {
			client.setMaxOnline(0);
		} else if (client.getMaxOnline() < 0 || client.getMaxOnline() > 9999) {
			throw new ServiceException("最大在线数请填 0（不限制）到 9999 的整数");
		}
		if (client.getTokenTimeout() == null) {
			client.setTokenTimeout(2592000);
		} else if (client.getTokenTimeout() < 60 || client.getTokenTimeout() > 31536000) {
			throw new ServiceException("令牌有效期请填 60 秒到 365 天之间的秒数");
		}
		if (client.getStatus() == null) {
			client.setStatus(1);
		} else if (client.getStatus() != 0 && client.getStatus() != 1) {
			throw new ServiceException("状态只能选择启用或停用");
		}
		String remark = client.getRemark() == null ? "" : client.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		client.setRemark(remark.isEmpty() ? null : remark);
	}
}
