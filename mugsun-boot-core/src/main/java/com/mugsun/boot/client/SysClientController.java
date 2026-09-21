package com.mugsun.boot.client;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.client.mapper.SysClientMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 登录客户端策略管理：CRUD + 启停。sys_client 无租户列，平台级配置。
 */
@RestController
@RequestMapping("/system/client")
@SaCheckLogin
public class SysClientController {

	private final SysClientMapper clientMapper;

	public SysClientController(SysClientMapper clientMapper) {
		this.clientMapper = clientMapper;
	}

	@GetMapping("/page")
	public R<Page<SysClient>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(clientMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@SaCheckPermission("sys:client:save")
	@PostMapping("/save")
	public R<Void> save(@RequestBody SysClient client) {
		if (client.getClientId() == null || client.getClientId().isBlank()) {
			throw new ServiceException("客户端码不能为空");
		}
		if (client.getId() == null) {
			SysClient exist = clientMapper.selectOneByQuery(
				QueryWrapper.create().eq("client_id", client.getClientId()));
			if (exist != null) {
				throw new ServiceException("客户端码已存在");
			}
			if (client.getStatus() == null) {
				client.setStatus(1);
			}
			clientMapper.insertSelective(client);
		} else {
			clientMapper.update(client);
		}
		return R.success("保存成功");
	}

	@SaCheckPermission("sys:client:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids != null && !ids.isEmpty()) {
			clientMapper.deleteBatchByIds(ids);
		}
		return R.success("删除成功");
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
		UpdateChain.of(SysClient.class).set(SysClient::getStatus, status).where(SysClient::getId).eq(id).update();
		return R.success("操作成功");
	}
}
