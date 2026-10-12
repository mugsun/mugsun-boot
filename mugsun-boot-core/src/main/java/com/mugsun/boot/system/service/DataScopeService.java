package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysDataScope;
import com.mugsun.boot.system.mapper.SysDataScopeMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据权限规则门面：跨域装载启用规则，勿直连 {@link SysDataScopeMapper}。
 */
@Service
public class DataScopeService {

	private final SysDataScopeMapper dataScopeMapper;

	public DataScopeService(SysDataScopeMapper dataScopeMapper) {
		this.dataScopeMapper = dataScopeMapper;
	}

	/** 启用中的规则（status=1）。调用方自行决定是否 ignore 租户。 */
	public List<SysDataScope> listEnabled() {
		return dataScopeMapper.selectListByQuery(QueryWrapper.create().eq("status", 1));
	}
}
