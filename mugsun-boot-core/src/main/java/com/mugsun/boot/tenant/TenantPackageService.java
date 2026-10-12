package com.mugsun.boot.tenant;

import com.mugsun.boot.tenant.entity.SysTenantPackage;
import com.mugsun.boot.tenant.mapper.SysTenantPackageMapper;
import org.springframework.stereotype.Service;

/**
 * 租户套餐门面：跨域按 id 查套餐，勿直连 {@link SysTenantPackageMapper}。
 */
@Service
public class TenantPackageService {

	private final SysTenantPackageMapper packageMapper;

	public TenantPackageService(SysTenantPackageMapper packageMapper) {
		this.packageMapper = packageMapper;
	}

	public SysTenantPackage getById(Long id) {
		if (id == null) {
			return null;
		}
		return packageMapper.selectOneById(id);
	}
}
