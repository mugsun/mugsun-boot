package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenDatasource;
import com.mugsun.boot.gen.mapper.GenDatasourceMapper;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑时改了连接地址而口令留空：保存被拒绝，不去连新地址、不写库，库里的口令不会跟到新地址。
 * 新地址用本机一个监听端口顶替，能直接看到有没有连接进来。
 */
class GenDatasourceSecretFollowsAddressTest {

	private static final String ELSEWHERE = "jdbc:postgresql://db-old.example.com:5432/app";

	private final GenDatasourceMapper mapper = Mockito.mock(GenDatasourceMapper.class);
	private final GenDatasourceService service = new GenDatasourceService(mapper, Mockito.mock(GenService.class));
	private ServerSocket listener;
	private String listenerUrl;
	private CompletableFuture<Boolean> connected;

	@BeforeEach
	void listen() throws IOException {
		listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
		listener.setSoTimeout(1500);
		listenerUrl = "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/app";
		ServerSocket socket = listener;
		// 只等第一条连接：来了就记下并关掉监听，驱动重试时马上被拒绝，不用等登录超时
		connected = CompletableFuture.supplyAsync(() -> {
			try (ServerSocket closing = socket; Socket ignored = socket.accept()) {
				return true;
			} catch (IOException timedOutOrClosed) {
				return false;
			}
		});
	}

	@AfterEach
	void stopListening() throws IOException {
		listener.close();
	}

	private static GenDatasource stored(String url) {
		GenDatasource row = new GenDatasource();
		row.setId(5L);
		row.setDsName("测试数据源");
		row.setJdbcUrl(url);
		row.setUsername("reader");
		row.setPassword("stored-pass");
		return row;
	}

	@Test
	void newAddressWithoutPasswordIsRejectedAndNothingConnects() throws Exception {
		Mockito.when(mapper.selectOneById(5L)).thenReturn(stored(ELSEWHERE));

		for (String blank : new String[] {null, "", "   "}) {
			ServiceException e = assertThrows(ServiceException.class,
				() -> service.save(5L, "测试数据源", listenerUrl, "reader", blank, ""));
			assertTrue(e.getMessage().contains("连接地址改了，请同时重新填写数据库口令。"), e.getMessage());
		}

		assertFalse(connected.get(5, TimeUnit.SECONDS), "被拒绝的保存不应该去连新地址");
		Mockito.verify(mapper, Mockito.never()).update(Mockito.any(GenDatasource.class));
	}

	@Test
	void newAddressWithNewPasswordIsProbedAndSaved() throws Exception {
		Mockito.when(mapper.selectOneById(5L)).thenReturn(stored(ELSEWHERE));

		service.save(5L, "测试数据源", listenerUrl, "reader", "new-pass", "");

		assertTrue(connected.get(5, TimeUnit.SECONDS), "填了新口令时照常检查连通性");
		Mockito.verify(mapper).update(Mockito.argThat((GenDatasource row) -> "new-pass".equals(row.getPassword())
			&& listenerUrl.equals(row.getJdbcUrl())));
	}

	@Test
	void sameAddressStillKeepsTheSavedPassword() throws Exception {
		Mockito.when(mapper.selectOneById(5L)).thenReturn(stored(listenerUrl));

		service.save(5L, "改个名字", listenerUrl, "reader", "", "");

		assertTrue(connected.get(5, TimeUnit.SECONDS), "地址没变时照常检查连通性");
		Mockito.verify(mapper).update(Mockito.argThat((GenDatasource row) -> "stored-pass".equals(row.getPassword())
			&& "改个名字".equals(row.getDsName())));
	}
}
