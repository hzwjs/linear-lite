-- 任务和项目文档共用一条索引任务路径；迁移后旧任务队列表不再参与读写。
CREATE TABLE IF NOT EXISTS project_content_semantic_index_jobs (
    content_type VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    content_hash CHAR(64) DEFAULT NULL,
    run_after DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (content_type, resource_id),
    INDEX idx_project_content_semantic_jobs_due (run_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO project_content_semantic_index_jobs
    (content_type, resource_id, operation, content_hash, run_after, version, attempts)
SELECT 'TASK', task_id, operation, content_hash, run_after, version, attempts
FROM task_semantic_index_jobs
ON DUPLICATE KEY UPDATE
    operation = VALUES(operation),
    content_hash = VALUES(content_hash),
    run_after = VALUES(run_after),
    version = GREATEST(project_content_semantic_index_jobs.version, VALUES(version)),
    attempts = VALUES(attempts);

DROP TABLE task_semantic_index_jobs;
ALTER TABLE tasks DROP COLUMN semantic_index_hash;
