-- AI 模块补强：知识库正文列 + 向量表
-- 有 pgvector 才建 embedding 列与 HNSW；没有扩展时表仍建成，检索回落关键词。
-- 不能在扩展失败后无条件使用 vector 类型，否则 Flyway 整次失败（basic 也会跑本脚本）。

ALTER TABLE ai_knowledge_asset ADD COLUMN content_text TEXT;
CREATE TABLE ai_kb_vector (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	knowledge_id    BIGINT NOT NULL,
	segment_id      BIGINT NOT NULL,
	asset_id        BIGINT,
	dims            INT NOT NULL DEFAULT 768,
	content_preview VARCHAR(512),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_ai_kb_vector_seg ON ai_kb_vector (segment_id);
CREATE INDEX idx_ai_kb_vector_kb ON ai_kb_vector (knowledge_id);

