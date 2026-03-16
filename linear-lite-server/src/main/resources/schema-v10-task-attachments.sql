USE linear_lite;

CREATE TABLE IF NOT EXISTS task_attachments (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id      BIGINT       NOT NULL,
    object_key   VARCHAR(512) NOT NULL,
    file_name    VARCHAR(256) NOT NULL,
    file_size    BIGINT       NOT NULL,
    content_type VARCHAR(128) DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_attachments_task_id ON task_attachments (task_id);
