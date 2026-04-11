-- 已有库增量：任务评论父子层级字段（无外键）
-- 执行：mysql ... < schema-v12-comment-threading.sql

ALTER TABLE task_comments
    ADD COLUMN parent_id BIGINT DEFAULT NULL COMMENT '父评论 ID，NULL 表示顶层评论' AFTER body,
    ADD COLUMN root_id BIGINT DEFAULT NULL COMMENT '根评论 ID，顶层评论可为 NULL' AFTER parent_id,
    ADD COLUMN depth INT NOT NULL DEFAULT 0 COMMENT '评论层级深度，顶层为 0' AFTER root_id;

CREATE INDEX idx_task_comments_parent_id ON task_comments (parent_id);
CREATE INDEX idx_task_comments_root_id ON task_comments (root_id);
