package com.mugsun.boot.system.service;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.mugsun.boot.system.entity.SysDict;
import com.mugsun.boot.system.mapper.SysDictMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典服务：字典项查询走本地缓存，写操作按编码失效
 */
@Service
public class DictService {

	private final SysDictMapper dictMapper;
	private final org.springframework.beans.factory.ObjectProvider<DictService> self;

	public DictService(SysDictMapper dictMapper, org.springframework.beans.factory.ObjectProvider<DictService> self) {
		this.dictMapper = dictMapper;
		this.self = self;
	}

	/** 按编码查字典项（缓存，排除分类节点）。
	 *  字典为平台全局数据（sys_dict 无 tenant_id），缓存键必须租户无关——经 ignore 固定「-」前缀，
	 *  否则同一字典按请求方租户重复缓存、管理端 evict 只清自己上下文（跨租户最长 1h 不生效）。 */
	public List<SysDict> listItems(String code) {
		return com.mugsun.boot.tenant.TenantContext.ignore(() -> self.getObject().cachedListItems(code));
	}

	/** 失效指定编码的字典项缓存（同 listItems 统一租户无关键） */
	public void evict(String code) {
		com.mugsun.boot.tenant.TenantContext.ignore(() -> {
			self.getObject().evictInternal(code);
			return null;
		});
	}

	@Cached(name = "dict:item:", key = "#code", cacheType = CacheType.LOCAL)
	public List<SysDict> cachedListItems(String code) {
		return dictMapper.selectListByQuery(QueryWrapper.create()
			.eq("code", code).ne("parent_id", 0L).orderBy("sort", true));
	}

	@CacheInvalidate(name = "dict:item:", key = "#code")
	public void evictInternal(String code) {
	}

	/** 按编码+键翻译为字典标签（找不到原样返回值） */
	public String translate(String code, String value) {
		if (value == null) {
			return null;
		}
		for (SysDict item : listItems(code)) {
			if (value.equals(item.getDictKey())) {
				return item.getDictValue();
			}
		}
		return value;
	}
}
