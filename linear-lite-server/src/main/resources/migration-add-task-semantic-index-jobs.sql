-- 已有库增量：任务语义索引采用去重延迟队列，避免自动保存频繁调用 Embedding。
ALTER TABLE tasks ADD COLUMN semantic_index_hash CHAR(64) DEFAULT NULL COMMENT '最近成功写入语义索引的标题/描述内容哈希';

CREATE TABLE IF NOT EXISTS task_semantic_index_jobs (
    task_id      BIGINT       NOT NULL PRIMARY KEY,
    operation    VARCHAR(16)  NOT NULL COMMENT 'UPSERT 或 DELETE',
    content_hash CHAR(64)     DEFAULT NULL,
    run_after    DATETIME     NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 1,
    attempts     INT UNSIGNED NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_semantic_index_jobs_due (run_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
