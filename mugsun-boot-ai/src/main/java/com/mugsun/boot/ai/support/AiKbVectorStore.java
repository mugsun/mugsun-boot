package com.mugsun.boot.ai.support;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库向量读写（pgvector）。固定 768 维：不足补 0、超出截断，兼容 Ollama nomic / 其它模型。
 */
@Component
public class AiKbVectorStore {

	public static final int DIMS = 768;

	private final JdbcTemplate jdbcTemplate;

	public AiKbVectorStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void upsert(Long knowledgeId, Long segmentId, Long assetId, float[] embedding, String preview) {
		if (!isVectorAvailable()) {
			return;
		}
		float[] v = normalize(embedding);
		String lit = toVectorLiteral(v);
		String tenant = TenantContext.current();
		Long id = IdUtil.getSnowflakeNextId();
		int updated = jdbcTemplate.update("""
			UPDATE ai_kb_vector SET embedding = ?::vector, content_preview = ?, update_time = NOW(), is_deleted = 0,
				asset_id = ?, dims = ?, tenant_id = COALESCE(?, tenant_id)
			WHERE segment_id = ? AND is_deleted = 0
			""", lit, preview, assetId, DIMS, tenant, segmentId);
		if (updated == 0) {
			jdbcTemplate.update("""
				INSERT INTO ai_kb_vector (id, tenant_id, knowledge_id, segment_id, asset_id, dims, embedding, content_preview, create_time, update_time, is_deleted)
				VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, NOW(), NOW(), 0)
				""", id, tenant, knowledgeId, segmentId, assetId, DIMS, lit, preview);
		}
	}

	public void deleteBySegment(Long segmentId) {
		jdbcTemplate.update("UPDATE ai_kb_vector SET is_deleted = 1, update_time = NOW() WHERE segment_id = ?", segmentId);
	}

	public void deleteByKnowledge(Long knowledgeId) {
		jdbcTemplate.update("UPDATE ai_kb_vector SET is_deleted = 1, update_time = NOW() WHERE knowledge_id = ?", knowledgeId);
	}

	public List<Map<String, Object>> search(Long knowledgeId, float[] query, int topK) {
		return search(knowledgeId, query, topK, null);
	}

	/**
	 * @param minScore 相似度下限（0~1，余弦等价：1 - cosine_distance）；null 不过滤
	 */
	public List<Map<String, Object>> search(Long knowledgeId, float[] query, int topK, Double minScore) {
		if (!isVectorAvailable()) {
			return List.of();
		}
		float[] v = normalize(query);
		String lit = toVectorLiteral(v);
		int k = Math.max(1, Math.min(topK, 50));
		double floor = minScore == null ? Double.NEGATIVE_INFINITY : minScore;
		try {
			return jdbcTemplate.query("""
				SELECT segment_id, content_preview,
					1 - (embedding <=> ?::vector) AS score
				FROM ai_kb_vector
				WHERE knowledge_id = ? AND is_deleted = 0 AND embedding IS NOT NULL
				ORDER BY embedding <=> ?::vector
				LIMIT ?
				""", (rs, rowNum) -> {
				Map<String, Object> m = new HashMap<>();
				m.put("segmentId", rs.getLong("segment_id"));
				m.put("contentPreview", rs.getString("content_preview"));
				m.put("score", rs.getDouble("score"));
				m.put("source", "vector");
				return m;
			}, lit, knowledgeId, lit, k).stream()
				.filter(m -> {
					Object sc = m.get("score");
					return sc instanceof Number n && n.doubleValue() >= floor;
				})
				.toList();
		} catch (Exception e) {
			throw new ServiceException("向量检索失败: " + e.getMessage());
		}
	}

	public boolean isVectorAvailable() {
		try {
			Integer n = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
			return n != null && n > 0;
		} catch (Exception e) {
			return false;
		}
	}

	public static float[] normalize(float[] src) {
		float[] out = new float[DIMS];
		if (src == null) {
			return out;
		}
		int n = Math.min(src.length, DIMS);
		System.arraycopy(src, 0, out, 0, n);
		return out;
	}

	public static String toVectorLiteral(float[] v) {
		StringBuilder sb = new StringBuilder(v.length * 8);
		sb.append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		sb.append(']');
		return sb.toString();
	}

	/** 无外部 embedding 时的确定性本地向量（仅保证管道可跑，质量弱于真模型） */
	public static float[] localHashEmbed(String text) {
		float[] v = new float[DIMS];
		if (text == null || text.isBlank()) {
			return v;
		}
		String[] tokens = text.toLowerCase().split("[\\s\\p{Punct}]+");
		for (String t : tokens) {
			if (t.isEmpty()) {
				continue;
			}
			int h = t.hashCode();
			int idx = Math.floorMod(h, DIMS);
			v[idx] += 1.0f;
			v[Math.floorMod(h * 31, DIMS)] += 0.5f;
		}
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		norm = Math.sqrt(norm);
		if (norm > 1e-8) {
			for (int i = 0; i < v.length; i++) {
				v[i] = (float) (v[i] / norm);
			}
		}
		return v;
	}
}
