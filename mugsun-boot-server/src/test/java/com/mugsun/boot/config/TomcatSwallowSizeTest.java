package com.mugsun.boot.config;

import com.mugsun.boot.track.AbstractTrackIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护：Tomcat max-swallow-size 必须不小于 multipart max-request-size，
 * 否则超限上传被拒后剩余请求体超吞吐上限会断连（spring-boot#41581），客户端收不到 400。
 * 以后调大上传上限须同步调大 server.tomcat.max-swallow-size。
 */
class TomcatSwallowSizeTest extends AbstractTrackIntegrationTest {

	@Autowired
	private ServerProperties serverProperties;

	@Autowired
	private MultipartProperties multipartProperties;

	@Test
	void maxSwallowSizeCoversMultipartMaxRequestSize() {
		long swallow = serverProperties.getTomcat().getMaxSwallowSize().toBytes();
		long maxRequest = multipartProperties.getMaxRequestSize().toBytes();
		assertThat(maxRequest).isPositive();
		assertThat(swallow).isGreaterThanOrEqualTo(maxRequest);
	}
}
