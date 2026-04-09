-- Linear Lite 表结构 + 可选种子数据（本项目不使用外键，表间关联由应用层维护）
-- 执行前请先创建数据库：CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;
-- 全新库：执行本文件即可。原 schema-v3～v10、data-init 已合并于此；已有旧库若缺列/表请自行 ALTER 或从 git 历史取增量脚本。


-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    avatar_url  VARCHAR(512) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    code        VARCHAR(16)  NOT NULL,
    purpose     VARCHAR(32)  NOT NULL,
    expires_at  DATETIME     NOT NULL,
    used_at     DATETIME     DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_email_verification_codes_email_purpose
ON email_verification_codes (email, purpose, created_at);

-- 项目表
CREATE TABLE IF NOT EXISTS projects (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    identifier  VARCHAR(16)  NOT NULL UNIQUE COMMENT 'Issue ID 前缀，如 ENG, PROD',
    creator_id  BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_projects_creator_id ON projects (creator_id);

CREATE TABLE IF NOT EXISTS project_members (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    role        VARCHAR(32)  NOT NULL DEFAULT 'member',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_members_project_user (project_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_members_user_id ON project_members (user_id);

CREATE TABLE IF NOT EXISTS project_invitations (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    email        VARCHAR(255) NOT NULL,
    invited_by   BIGINT       NOT NULL,
    accepted_at  DATETIME     DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_invitations_project_email ON project_invitations (project_id, email, created_at);

-- 任务表（task_key 为对外展示的任务 ID，格式：项目 identifier + '-' + 项目内序号，如 ENG-1, PROD-2）
CREATE TABLE IF NOT EXISTS tasks (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_key    VARCHAR(32)  NOT NULL UNIQUE COMMENT '带项目前缀的任务 ID，如 ENG-1',
    title       VARCHAR(256) NOT NULL,
    description TEXT         DEFAULT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'backlog',
    priority    VARCHAR(16)  DEFAULT 'medium',
    project_id  BIGINT       NOT NULL,
    parent_id   BIGINT       DEFAULT NULL COMMENT '父任务 ID，NULL 表示顶层任务',
    creator_id  BIGINT       NOT NULL,
    assignee_id BIGINT       DEFAULT NULL,
    assignee_display_name VARCHAR(128) DEFAULT NULL COMMENT '导入或外部处理人展示名，无系统用户时使用',
    due_date    DATETIME     DEFAULT NULL COMMENT '预计完成/截止日期',
    planned_start_date DATETIME DEFAULT NULL COMMENT '计划开始日期',
    progress_percent TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '完成进度 0–100',
    completed_at DATETIME    DEFAULT NULL COMMENT '实际完成时间，终态时由系统写入',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_tasks_project_id ON tasks (project_id);
CREATE INDEX idx_tasks_parent_id  ON tasks (parent_id);
CREATE INDEX idx_tasks_task_key ON tasks (task_key);
CREATE INDEX idx_tasks_creator_id  ON tasks (creator_id);
CREATE INDEX idx_tasks_assignee_id ON tasks (assignee_id);

-- 统计模块复合索引（仅索引，无外键）
CREATE INDEX idx_tasks_project_created_at ON tasks (project_id, created_at);
CREATE INDEX idx_tasks_project_completed_at ON tasks (project_id, completed_at);
CREATE INDEX idx_tasks_project_due_date ON tasks (project_id, due_date);
CREATE INDEX idx_tasks_project_status ON tasks (project_id, status);
CREATE INDEX idx_tasks_project_assignee ON tasks (project_id, assignee_id);
CREATE INDEX idx_tasks_project_priority ON tasks (project_id, priority);

CREATE TABLE IF NOT EXISTS task_favorites (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    task_id     BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_favorites_user_task (user_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_favorites_user_id ON task_favorites (user_id);
CREATE INDEX idx_task_favorites_task_id ON task_favorites (task_id);

CREATE TABLE IF NOT EXISTS task_activities (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    action_type VARCHAR(32)  NOT NULL,
    field_name  VARCHAR(64)  DEFAULT NULL,
    old_value   TEXT         DEFAULT NULL,
    new_value   TEXT         DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_activities_task_id ON task_activities (task_id);
CREATE INDEX idx_task_activities_user_id ON task_activities (user_id);

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

-- 任务标签（项目级词典 + 关联，无外键）
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

-- 任务评论（逻辑关联 tasks.id / users.id，无外键）
CREATE TABLE IF NOT EXISTS task_comments (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id     BIGINT       NOT NULL COMMENT '逻辑关联 tasks.id',
    author_id   BIGINT       NOT NULL COMMENT '逻辑关联 users.id',
    body        TEXT         NOT NULL COMMENT '与 tasks.description 同格式（如 Markdown）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_comments_task_id ON task_comments (task_id, created_at, id);

-- 评论中的 @ 提及（逻辑关联 task_comments.id / users.id）
CREATE TABLE IF NOT EXISTS comment_mentions (
    id                   BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    comment_id           BIGINT NOT NULL,
    mentioned_user_id    BIGINT NOT NULL,
    UNIQUE KEY uk_comment_mentions_comment_user (comment_id, mentioned_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_comment_mentions_mentioned_user ON comment_mentions (mentioned_user_id);

-- 站内通知（逻辑关联 users.id / tasks.id / task_comments.id）
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

-- ========== 种子数据（可选；密码为明文占位，正式环境需改为 BCrypt 等）==========

INSERT INTO users (username, email, password, avatar_url) VALUES
    ('admin',  'admin@example.com',  'admin123',  NULL),
    ('user1',  'user1@example.com',  'user123',   NULL),
    ('user2',  'user2@example.com',  'user123',   NULL),
    ('alice',  'alice@example.com',  'alice123',  NULL),
    ('bob',    'bob@example.com',    'bob123',    NULL)
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    email = VALUES(email);

INSERT INTO projects (name, identifier, creator_id)
SELECT 'Engineering', 'ENG', id FROM users WHERE username = 'admin'
UNION ALL
SELECT 'Design', 'DES', id FROM users WHERE username = 'alice'
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    creator_id = VALUES(creator_id);

INSERT INTO project_members (project_id, user_id, role)
SELECT id, creator_id, 'owner' FROM projects
ON DUPLICATE KEY UPDATE role = VALUES(role);
