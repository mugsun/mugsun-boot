package com.mugsun.boot.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowGraphAiNodeTest {

	private final FlowGraphService svc = new FlowGraphService(new ObjectMapper());
	private final ObjectMapper om = new ObjectMapper();

	@Test
	void aiNodeWritesListenerAndExt() throws Exception {
		FlowGraphService.TreeNode human = new FlowGraphService.TreeNode(
			"approval", "人工", List.of("role:admin"), null, "0",
			null, null, null, null, null, null, null, null);
		FlowGraphService.TreeNode ai = new FlowGraphService.TreeNode(
			"ai", "预审", null, null, null, null, null, human,
			42L, Map.of("reason", "input"), null, 60_000L, "stay");
		String json = svc.buildJson(new FlowGraphService.GraphDesign(
			"ai_demo", "AI演示", "lab", null, ai));
		JsonNode root = om.readTree(json);
		JsonNode aiNode = null;
		for (JsonNode n : root.path("nodeList")) {
			if ("create".equals(n.path("listenerType").asText())) {
				aiNode = n;
				break;
			}
		}
		assertNotNull(aiNode);
		assertEquals(1, aiNode.path("nodeType").asInt());
		assertEquals(FlowConstants.AI_APPROVAL_LISTENER, aiNode.path("listenerPath").asText());
		assertEquals(FlowConstants.PLACEHOLDER_INITIATOR, aiNode.path("permissionFlag").asText());
		JsonNode ext = om.readTree(aiNode.path("ext").asText());
		assertEquals(42L, ext.path("aiFlowId").asLong());
		assertEquals(60_000L, ext.path("timeoutMs").asLong());
		assertEquals("stay", ext.path("onFailure").asText());
		assertEquals("input", ext.path("inputMap").path("reason").asText());
	}

	@Test
	void aiNodeRequiresFlowId() {
		FlowGraphService.TreeNode ai = new FlowGraphService.TreeNode(
			"ai", "预审", null, null, null, null, null, null,
			null, null, null, null, null);
		assertThrows(ServiceException.class, () -> svc.buildJson(
			new FlowGraphService.GraphDesign("c", "n", null, null, ai)));
	}

	@Test
	void aiNodeRejectsBadOnFailure() {
		FlowGraphService.TreeNode ai = new FlowGraphService.TreeNode(
			"ai", "预审", null, null, null, null, null, null,
			1L, null, null, null, "boom");
		assertThrows(ServiceException.class, () -> svc.buildJson(
			new FlowGraphService.GraphDesign("c", "n", null, null, ai)));
	}

	@Test
	void aiNodeRejectsTimeoutOverCap() {
		FlowGraphService.TreeNode ai = new FlowGraphService.TreeNode(
			"ai", "预审", null, null, null, null, null, null,
			1L, null, null, 400_000L, "stay");
		assertThrows(ServiceException.class, () -> svc.buildJson(
			new FlowGraphService.GraphDesign("c", "n", null, null, ai)));
	}

	@Test
	void aiNodeRejectsBadOutputKey() {
		FlowGraphService.TreeNode ai = new FlowGraphService.TreeNode(
			"ai", "预审", null, null, null, null, null, null,
			1L, null, Map.of("x", "permissionFlag"), 60_000L, "stay");
		assertThrows(ServiceException.class, () -> svc.buildJson(
			new FlowGraphService.GraphDesign("c", "n", null, null, ai)));
	}
}
