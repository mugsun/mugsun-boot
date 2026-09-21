package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysSms;
import com.mugsun.boot.system.entity.SysSmsCode;
import com.mugsun.boot.system.mapper.SysSmsCodeMapper;
import com.mugsun.boot.system.mapper.SysSmsMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.sms4j.aliyun.config.AlibabaConfig;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.dromara.sms4j.core.datainterface.SmsReadConfig;
import org.dromara.sms4j.core.factory.SmsFactory;
import org.dromara.sms4j.provider.config.BaseConfig;
import org.dromara.sms4j.tencent.config.TencentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * 短信服务：验证码发送与校验。发送通道由 sys_sms 启用配置驱动（SMS4J SmsReadConfig），
 * 无启用配置/凭证占位/通道异常即抛错（显式降级，不再假成功），支持前端切换渠道运行时生效。
 */
@Service
public class SmsService {

	private static final Logger log = LoggerFactory.getLogger(SmsService.class);

	/** 发送节流锁键前缀（同号 60s 一码） */
	private static final String THROTTLE_KEY = "mugsun:sms:throttle:";

	private final SysSmsCodeMapper smsCodeMapper;
	private final SysSmsMapper smsMapper;
	private final org.springframework.data.redis.core.StringRedisTemplate redis;
	private final Random random = new Random();

	/** 开发环境回显验证码（生产须 false） */
	@Value("${mugsun.sms.show-code:false}")
	private boolean showCode;

	public SmsService(SysSmsCodeMapper smsCodeMapper, SysSmsMapper smsMapper,
					  org.springframework.data.redis.core.StringRedisTemplate redis) {
		this.smsCodeMapper = smsCodeMapper;
		this.smsMapper = smsMapper;
		this.redis = redis;
	}

	/** 是否回显验证码（开发便于联调，生产关闭） */
	public boolean isShowCode() {
		return showCode;
	}

	/** 取该手机号最近一条未过期验证码（仅供开发回显，勿用于生产鉴权） */
	public String peekCode(String phone) {
		SysSmsCode rec = smsCodeMapper.selectOneByQuery(QueryWrapper.create()
			.eq("phone", phone).ge("expire_time", LocalDateTime.now()).orderBy("id", false));
		return rec == null ? null : rec.getCode();
	}

	/** 发送验证码：同号 60s 节流（防短信轰炸），发新作废旧码（任意时刻仅一码有效）。
	 *  通道显式降级：无启用配置/下发失败即抛错并作废刚写入的验证码，不再静默降级假成功 */
	public void sendCode(String phone) {
		Boolean first = redis.opsForValue().setIfAbsent(THROTTLE_KEY + phone, "1", Duration.ofSeconds(60));
		if (!Boolean.TRUE.equals(first)) {
			throw new ServiceException("发送过于频繁，请 60 秒后再试");
		}
		// 验证码通道属平台级，登录前无租户上下文，故不加租户条件取启用配置
		SysSms sms = TenantContext.ignore(() ->
			smsMapper.selectOneByQuery(QueryWrapper.create().eq("status", 1).orderBy("id", false)));
		if (sms == null) {
			// 通道未配置是稳定服务端状态，释放节流锁让显式错误每次可见
			redis.delete(THROTTLE_KEY + phone);
			throw new ServiceException("短信通道未配置");
		}
		smsCodeMapper.deleteByQuery(QueryWrapper.create().eq("phone", phone));
		String code = String.valueOf(100000 + random.nextInt(900000));
		SysSmsCode entity = new SysSmsCode();
		entity.setPhone(phone);
		entity.setCode(code);
		entity.setExpireTime(LocalDateTime.now().plusMinutes(5));
		smsCodeMapper.insertSelective(entity);

		try {
			SmsFactory.createSmsBlend(readConfigOf(sms));
			SmsResponse resp = SmsFactory.getSmsBlend(sms.getSmsCode()).sendMessage(phone, code);
			if (resp == null || !resp.isSuccess()) {
				throw new ServiceException("短信下发未成功(凭证占位或网关拒绝): " + sms.getSmsCode());
			}
			log.info("短信配置[{}/{}]下发成功 phone={}", sms.getSmsCode(), sms.getCategory(), phone);
		} catch (ServiceException e) {
			// 下发失败作废验证码：不留「未送达却可登录」的悬空码
			smsCodeMapper.deleteByQuery(QueryWrapper.create().eq("phone", phone));
			throw e;
		} catch (Exception e) {
			smsCodeMapper.deleteByQuery(QueryWrapper.create().eq("phone", phone));
			throw new ServiceException("短信通道异常: " + e.getMessage());
		}
	}

	/** 以启用的 sys_sms 行构建 SMS4J 配置源（当前支持 alibaba/tencent，默认 alibaba） */
	private SmsReadConfig readConfigOf(SysSms sms) {
		BaseConfig config = "tencent".equalsIgnoreCase(sms.getCategory()) ? new TencentConfig() : new AlibabaConfig();
		config.setConfigId(sms.getSmsCode());
		config.setFactory(sms.getCategory());
		config.setAccessKeyId(sms.getAccessKey());
		config.setAccessKeySecret(sms.getSecretKey());
		config.setSignature(sms.getSignature());
		config.setTemplateId(sms.getTemplateId());
		List<BaseConfig> list = List.of(config);
		return new SmsReadConfig() {
			@Override
			public BaseConfig getSupplierConfig(String configId) {
				return sms.getSmsCode().equals(configId) ? config : null;
			}

			@Override
			public List<BaseConfig> getSupplierConfigList() {
				return list;
			}
		};
	}

	/**
	 * 通用文本短信发送（统一通知调度用）：无启用配置或下发未成功即抛异常——
	 * 与验证码的降级日志语义相反，调度侧须拿到真实回执（FAILURE 留痕 + 重试）。
	 */
	public void sendText(String phone, String text) {
		SysSms sms = TenantContext.ignore(() ->
			smsMapper.selectOneByQuery(QueryWrapper.create().eq("status", 1).orderBy("id", false)));
		if (sms == null) {
			throw new ServiceException("无启用短信配置");
		}
		try {
			SmsFactory.createSmsBlend(readConfigOf(sms));
			SmsResponse resp = SmsFactory.getSmsBlend(sms.getSmsCode()).sendMessage(phone, text);
			if (resp == null || !resp.isSuccess()) {
				throw new ServiceException("短信下发未成功(凭证占位或网关拒绝): " + sms.getSmsCode());
			}
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException("短信通道异常: " + e.getMessage());
		}
	}

	/** 校验验证码：命中未过期记录则通过并作废 */
	public boolean verifyCode(String phone, String code) {
		SysSmsCode rec = smsCodeMapper.selectOneByQuery(QueryWrapper.create()
			.eq("phone", phone).eq("code", code)
			.ge("expire_time", LocalDateTime.now())
			.orderBy("id", false));
		if (rec == null) {
			return false;
		}
		smsCodeMapper.deleteById(rec.getId());
		return true;
	}
}
