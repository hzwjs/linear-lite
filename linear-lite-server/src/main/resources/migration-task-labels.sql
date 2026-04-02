-- 任务标签：项目级词典 + 任务关联（无数据库外键）
CREATE TABLE IF NOT EXISTS labels (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT       NOT NULL COMMENT '逻辑关联 projects.id',
    name        VARCHAR(64)  NOT NULL COMMENT '项目内唯一展示名',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_labels_project_name (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_labels_project_id ON labels (project_id);

CREATE TABLE IF NOT EXISTS task_labels (
    task_id  BIGINT NOT NULL COMMENT '逻辑关联 tasks.id',
    label_id BIGINT NOT NULL COMMENT '逻辑关联 labels.id',
    PRIMARY KEY (task_id, label_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_labels_label_id ON task_labels (label_id);
