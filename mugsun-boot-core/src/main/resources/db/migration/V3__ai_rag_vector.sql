-- AI 模块补强：知识库正文列 + 向量表
-- 有 pgvector 才建 embedding 列与 HNSW；没有扩展时表仍建成，检索回落关键词。
-- 不能在扩展失败后无条件使用 vector 类型，否则 Flyway 整次失败（basic 也会跑本脚本）。

DO $$
DECLARE
	has_vector boolean;
BEGIN
	BEGIN
		CREATE EXTENSION IF NOT EXISTS vector;
	EXCEPTION WHEN OTHERS THEN
		RAISE NOTICE 'pgvector 扩展不可用（%），向量检索将回落关键词', SQLERRM;
	END;

	SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') INTO has_vector;

	IF NOT EXISTS (
		SELECT 1 FROM information_schema.columns
		WHERE table_schema = current_schema()
		  AND table_name = 'ai_knowledge_asset'
		  AND column_name = 'content_text'
	) THEN
		ALTER TABLE ai_knowledge_asset ADD COLUMN content_text TEXT;
	END IF;

	IF has_vector THEN
		EXECUTE $ddl$
			CREATE TABLE IF NOT EXISTS ai_kb_vector (
				id              BIGINT PRIMARY KEY,
				tenant_id       VARCHAR(12),
				knowledge_id    BIGINT NOT NULL,
				segment_id      BIGINT NOT NULL,
				asset_id        BIGINT,
				dims            INT NOT NULL DEFAULT 768,
				embedding       vector(768),
				content_preview VARCHAR(512),
				create_time     TIMESTAMP,
				update_time     TIMESTAMP,
				is_deleted      INT NOT NULL DEFAULT 0
			)
		$ddl$;
		BEGIN
			EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ai_kb_vector_hnsw ON ai_kb_vector USING hnsw (embedding vector_cosine_ops)';
		EXCEPTION WHEN OTHERS THEN
			RAISE NOTICE 'HNSW 索引未创建（%），将使用顺序扫描', SQLERRM;
		END;
	ELSE
		EXECUTE $ddl$
			CREATE TABLE IF NOT EXISTS ai_kb_vector (
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
			)
		$ddl$;
	END IF;

	EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_kb_vector_seg ON ai_kb_vector (segment_id) WHERE is_deleted = 0';
	EXECUTE 'CREATE INDEX IF NOT EXISTS idx_ai_kb_vector_kb ON ai_kb_vector (knowledge_id) WHERE is_deleted = 0';
END $$;
