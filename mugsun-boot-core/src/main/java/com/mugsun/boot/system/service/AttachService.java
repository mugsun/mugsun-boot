package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 附件登记门面：跨域按 id 查删，勿直连 {@link SysAttachMapper}。
 */
@Service
public class AttachService {

	private final SysAttachMapper attachMapper;

	public AttachService(SysAttachMapper attachMapper) {
		this.attachMapper = attachMapper;
	}

	public SysAttach getById(Long id) {
		if (id == null) {
			return null;
		}
		return attachMapper.selectOneById(id);
	}

	public void deleteBatchByIds(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		attachMapper.deleteBatchByIds(List.copyOf(ids));
	}
}
