package com.mugsun.boot.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护测试：@Async/请求跑在虚拟线程上（JDK 21），在 synchronized 上等待会钉住载体线程。
 * 以下类的锁内有数据库/网络/IO 阻塞（操作日志落库、对象存储客户端重建、PowerJob 客户端登录），
 * 必须用 ReentrantLock，不得改回 synchronized（曾导致连接池永久耗尽、CI 卡死）。
 */
class VirtualThreadPinningGuardTest {

	private static final Path SRC = Path.of("src/main/java/com/mugsun/boot");

	@Test
	void blockingCriticalSectionsDoNotUseSynchronized() throws Exception {
		List<String> files = List.of(
			"log/OperationLogService.java",
			"system/service/OssService.java",
			"system/controller/JobController.java");
		for (String f : files) {
			String code = stripComments(Files.readString(SRC.resolve(f)));
			assertThat(code).as(f).doesNotContain("synchronized").contains("ReentrantLock");
		}
	}

	private static String stripComments(String s) {
		return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}
}
