package com.mugsun.boot.config;

import com.mugsun.boot.track.AbstractTrackIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护：Tomcat max-swallow-size 必须不小于 multipart max-request-size，
 * 否则超限上传被拒后剩余请求体超吞吐上限会断连（spring-boot#41581），客户端收不到 400。
 * 以后调大上传上限须同步调大 server.tomcat.max-swallow-size。
 */
class TomcatSwallowSizeTest extends AbstractTrackIntegrationTest {

	/** server.tomcat.*：Spring Boot 4 起从 ServerProperties 里拆到了单独的配置类 */
	@Autowired
	private TomcatServerProperties tomcatProperties;

	@Autowired
	private MultipartProperties multipartProperties;

	@Test
	void maxSwallowSizeCoversMultipartMaxRequestSize() {
		long swallow = tomcatProperties.getMaxSwallowSize().toBytes();
		long maxRequest = multipartProperties.getMaxRequestSize().toBytes();
		assertThat(maxRequest).isPositive();
		assertThat(swallow).isGreaterThanOrEqualTo(maxRequest);
	}
}
