package com.mugsun.boot.system.service;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.mugsun.boot.system.entity.SysParam;
import com.mugsun.boot.system.mapper.SysParamMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 参数服务：参数取值走本地缓存，写操作按键失效
 */
@Service
public class ParamService {

	private final SysParamMapper paramMapper;
	private final org.springframework.beans.factory.ObjectProvider<ParamService> self;

	public ParamService(SysParamMapper paramMapper, org.springframework.beans.factory.ObjectProvider<ParamService> self) {
		this.paramMapper = paramMapper;
		this.self = self;
	}

	/** 按键取参数值（缓存）。
	 *  参数为平台全局数据（sys_param 无 tenant_id），缓存键必须租户无关——经 ignore 固定「-」前缀（同 DictService）。 */
	public String getValue(String paramKey) {
		return com.mugsun.boot.tenant.TenantContext.ignore(() -> self.getObject().cachedGetValue(paramKey));
	}

	/** 失效指定键的参数缓存 */
	public void evict(String paramKey) {
		com.mugsun.boot.tenant.TenantContext.ignore(() -> {
			self.getObject().evictInternal(paramKey);
			return null;
		});
	}

	/** 写参数值（存在即更新，不存在插入；供运行期状态落参，如日志链截断锚点） */
	public void setValue(String paramKey, String paramValue) {
		SysParam param = paramMapper.selectOneByQuery(QueryWrapper.create().eq("param_key", paramKey));
		if (param == null) {
			param = new SysParam();
			param.setParamKey(paramKey);
			param.setParamValue(paramValue);
			param.setParamName(paramKey);
			paramMapper.insertSelective(param);
		} else {
			param.setParamValue(paramValue);
			paramMapper.update(param);
		}
		evict(paramKey);
	}

	@Cached(name = "param:", key = "#paramKey", cacheType = CacheType.LOCAL)
	public String cachedGetValue(String paramKey) {
		SysParam param = paramMapper.selectOneByQuery(QueryWrapper.create().eq("param_key", paramKey));
		return param == null ? null : param.getParamValue();
	}

	@CacheInvalidate(name = "param:", key = "#paramKey")
	public void evictInternal(String paramKey) {
	}
}
