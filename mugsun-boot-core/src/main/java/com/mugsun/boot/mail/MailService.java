package com.mugsun.boot.mail;

import com.mugsun.boot.mail.entity.SysMailTemplate;
import com.mugsun.boot.mail.mapper.SysMailTemplateMapper;
import com.mugsun.boot.notify.NotifyTemplateRenderer;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 邮件发送：按模板 ${key} 占位渲染后发送；通道未配置/下发异常即抛错（显式降级，同短信 sendText 语义）。
 * <p>渲染收编统一渲染器 {@link NotifyTemplateRenderer}（缺参 fail-fast，取代原"未提供占位原样保留"）。
 */
@Service
public class MailService {

	private static final Logger log = LoggerFactory.getLogger(MailService.class);

	private final JavaMailSender mailSender;
	private final SysMailTemplateMapper templateMapper;
	private final NotifyTemplateRenderer renderer;

	@Value("${spring.mail.username:noreply@mugsun.com}")
	private String from;
	@Value("${spring.mail.host:}")
	private String host;
	@Value("${spring.mail.password:}")
	private String password;

	public MailService(JavaMailSender mailSender, SysMailTemplateMapper templateMapper,
					   NotifyTemplateRenderer renderer) {
		this.mailSender = mailSender;
		this.templateMapper = templateMapper;
		this.renderer = renderer;
	}

	/**
	 * 通道健康检查：host/password 已填且非 application.yml 占位符才算已配置
	 * （占位 smtp.example.com/placeholder 仅供本地起服，不构成可用通道）。
	 */
	public boolean isConfigured() {
		return host != null && !host.isBlank() && !"smtp.example.com".equals(host)
			&& password != null && !password.isBlank() && !"placeholder".equals(password);
	}

	/** 按模板发送，返回渲染后内容（便于调试回显） */
	public String sendByTemplate(String to, String code, Map<String, String> params) {
		SysMailTemplate tpl = templateMapper.selectOneByQuery(QueryWrapper.create().eq("code", code));
		if (tpl == null) {
			throw new ServiceException("邮件模板不存在: " + code);
		}
		String content = renderer.render(tpl.getContent(), params);
		send(to, renderer.render(tpl.getSubject(), params), content);
		return content;
	}

	/** 发送纯文本邮件；通道未配置/下发异常即明确报错（显式降级，不再静默吞错假成功） */
	public void send(String to, String subject, String content) {
		if (!isConfigured()) {
			throw new ServiceException("邮件通道未配置");
		}
		try {
			SimpleMailMessage msg = new SimpleMailMessage();
			msg.setFrom(from);
			msg.setTo(to);
			msg.setSubject(subject);
			msg.setText(content);
			mailSender.send(msg);
			log.info("邮件已发送 to={} subject={}", to, subject);
		} catch (Exception e) {
			throw new ServiceException("邮件通道异常: " + e.getMessage());
		}
	}
}
