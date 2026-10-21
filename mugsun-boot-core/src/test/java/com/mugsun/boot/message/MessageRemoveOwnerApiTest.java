package com.mugsun.boot.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** /system/message/remove 只能删自己的收件记录：A 拿 B 的记录 id 去删，被拒且 B 的记录还在 */
class MessageRemoveOwnerApiTest extends AbstractIntegrationTest {

	@Test
	@DisplayName("A 删 B 的消息：拒绝，B 的记录未被删除；A 删自己的正常")
	void cannotRemoveOthersMessage() throws Exception {
		String tokenA = loginAdmin();
		long userA = userId(ADMIN_USERNAME);
		long userB = 990_000_000_000L + (System.nanoTime() % 1_000_000);
		long msgId = 990_100_000_000L + (System.nanoTime() % 1_000_000);
		long rowB = msgId + 1;
		long rowA = msgId + 2;
		try (Connection c = conn()) {
			insertRow(c, rowB, msgId, userB);
			insertRow(c, rowA, msgId, userA);
		}

		JsonNode r = readBody(post("/system/message/remove", List.of(rowB), tokenA));
		assertThat(r.path("success").asBoolean(true)).as(r.toString()).isFalse();
		assertThat(deleted(rowB)).isZero();

		// 混入自己的记录也整体拒绝，一条都不删
		r = readBody(post("/system/message/remove", List.of(rowA, rowB), tokenA));
		assertThat(r.path("success").asBoolean(true)).isFalse();
		assertThat(deleted(rowA)).isZero();
		assertThat(deleted(rowB)).isZero();

		// 对照：只删自己的能成功
		r = readBody(post("/system/message/remove", List.of(rowA), tokenA));
		assertThat(r.path("success").asBoolean()).as(r.toString()).isTrue();
		assertThat(deleted(rowA)).isEqualTo(1);
		assertThat(deleted(rowB)).isZero();
	}

	private static Connection conn() throws Exception {
		return DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword());
	}

	private static void insertRow(Connection c, long id, long msgId, long userId) throws Exception {
		try (PreparedStatement ps = c.prepareStatement(
			"INSERT INTO sys_message_user (id, message_id, user_id, is_read, create_time, update_time, is_deleted) "
				+ "VALUES (?, ?, ?, 0, now(), now(), 0)")) {
			ps.setLong(1, id);
			ps.setLong(2, msgId);
			ps.setLong(3, userId);
			ps.executeUpdate();
		}
	}

	/** 行不存在算 1（物理删除），is_deleted 原值返回 */
	private static int deleted(long id) throws Exception {
		try (Connection c = conn(); PreparedStatement ps = c.prepareStatement("SELECT is_deleted FROM sys_message_user WHERE id = ?")) {
			ps.setLong(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 1;
			}
		}
	}

	private static long userId(String username) throws Exception {
		try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
			"SELECT id FROM sys_user WHERE username = ? AND tenant_id = ?")) {
			ps.setString(1, username);
			ps.setString(2, PLATFORM_TENANT);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}
}
