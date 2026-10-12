package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import org.springframework.stereotype.Service;

/**
 * 菜单门面：跨域校验菜单是否存在，勿直连 {@link SysMenuMapper}。
 */
@Service
public class MenuService {

	private final SysMenuMapper menuMapper;

	public MenuService(SysMenuMapper menuMapper) {
		this.menuMapper = menuMapper;
	}

	public boolean exists(Long id) {
		if (id == null) {
			return false;
		}
		return menuMapper.selectOneById(id) != null;
	}

	public SysMenu getById(Long id) {
		if (id == null) {
			return null;
		}
		return menuMapper.selectOneById(id);
	}
}
