-- 已有库增量：任务评论、@ 提及、站内通知（无外键）
-- 执行：mysql ... < schema-v11-task-comments-notifications.sql

CREATE TABLE IF NOT EXISTS task_comments (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id     BIGINT       NOT NULL COMMENT '逻辑关联 tasks.id',
    author_id   BIGINT       NOT NULL COMMENT '逻辑关联 users.id',
    body        TEXT         NOT NULL COMMENT '与 tasks.description 同格式（如 Markdown）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_comments_task_id ON task_comments (task_id, created_at, id);

CREATE TABLE IF NOT EXISTS comment_mentions (
    id                   BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    comment_id           BIGINT NOT NULL,
    mentioned_user_id    BIGINT NOT NULL,
    UNIQUE KEY uk_comment_mentions_comment_user (comment_id, mentioned_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_comment_mentions_mentioned_user ON comment_mentions (mentioned_user_id);

CREATE TABLE IF NOT EXISTS in_app_notifications (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT '接收人',
    type        VARCHAR(32)  NOT NULL COMMENT '如 mention',
    task_id     BIGINT       NOT NULL,
    comment_id  BIGINT       NOT NULL,
    summary     VARCHAR(512) DEFAULT NULL,
    read_at     DATETIME     DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_in_app_notifications_user_created ON in_app_notifications (user_id, created_at DESC);
CREATE INDEX idx_in_app_notifications_user_unread ON in_app_notifications (user_id, read_at);
