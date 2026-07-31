-- 已有库增量：将项目内容索引任务升级为代次 + 租约模型。
-- 新环境直接执行 schema.sql，无需执行本脚本。
ALTER TABLE project_content_semantic_index_jobs
    DROP INDEX idx_project_content_semantic_jobs_due,
    CHANGE COLUMN version generation BIGINT NOT NULL DEFAULT 1,
    DROP COLUMN content_hash,
    ADD COLUMN lease_until DATETIME DEFAULT NULL AFTER attempts,
    ADD INDEX idx_project_content_semantic_jobs_due (run_after, lease_until);
