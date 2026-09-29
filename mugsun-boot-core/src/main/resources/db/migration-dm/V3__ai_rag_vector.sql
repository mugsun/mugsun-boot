-- pg2dm: manual
-- 达梦无 pgvector：知识库正文列 + 向量表（不含 embedding 列），检索走关键词。

ALTER TABLE ai_knowledge_asset ADD content_text CLOB;

CREATE TABLE ai_kb_vector (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	knowledge_id    BIGINT NOT NULL,
	segment_id      BIGINT NOT NULL,
	asset_id        BIGINT,
	dims            INT DEFAULT 768 NOT NULL,
	content_preview VARCHAR(512),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT DEFAULT 0 NOT NULL
);
CREATE UNIQUE INDEX uk_ai_kb_vector_seg ON ai_kb_vector (segment_id);
CREATE INDEX idx_ai_kb_vector_kb ON ai_kb_vector (knowledge_id);
