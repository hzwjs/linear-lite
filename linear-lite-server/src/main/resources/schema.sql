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
    user_type   VARCHAR(16)  NOT NULL DEFAULT 'human' COMMENT '用户领域类型：human/codex',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 已有库增量：user_type 是 Codex 系统负责人的唯一领域识别字段。
SET @users_user_type_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'user_type'
);
SET @users_user_type_ddl = IF(
    @users_user_type_exists = 0,
    'ALTER TABLE users ADD COLUMN user_type VARCHAR(16) NOT NULL DEFAULT ''human'' COMMENT ''用户领域类型：human/codex'' AFTER avatar_url',
    'SELECT 1'
);
PREPARE users_user_type_stmt FROM @users_user_type_ddl;
EXECUTE users_user_type_stmt;
DEALLOCATE PREPARE users_user_type_stmt;

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
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '当前用户侧栏中的项目顺序',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_members_project_user (project_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_members_user_id ON project_members (user_id);
CREATE INDEX idx_project_members_user_sort ON project_members (user_id, sort_order, id);

CREATE TABLE IF NOT EXISTS project_invitations (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    email        VARCHAR(255) NOT NULL,
    invited_by   BIGINT       NOT NULL,
    accepted_at  DATETIME     DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_invitations_project_email ON project_invitations (project_id, email, created_at);

CREATE TABLE IF NOT EXISTS project_task_seq (
    project_id   BIGINT NOT NULL PRIMARY KEY COMMENT '逻辑关联 projects.id',
    next_number  BIGINT NOT NULL COMMENT '下一个可分配任务序号（从 1 开始）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    semantic_index_hash CHAR(64) DEFAULT NULL COMMENT '最近成功写入语义索引的标题/描述内容哈希',
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

-- 语义索引异步任务：task_id 唯一，连续自动保存只会覆盖同一条待执行任务。
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
CREATE INDEX idx_task_activities_task_created_id ON task_activities (task_id, created_at, id);
CREATE INDEX idx_task_activities_coalesce ON task_activities (task_id, user_id, action_type, field_name, created_at, id);

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
    parent_id   BIGINT       DEFAULT NULL COMMENT '父评论 ID，NULL 表示顶层评论',
    root_id     BIGINT       DEFAULT NULL COMMENT '根评论 ID，顶层评论可为 NULL',
    depth       INT          NOT NULL DEFAULT 0 COMMENT '评论层级深度，顶层为 0',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_comments_task_id ON task_comments (task_id, created_at, id);
CREATE INDEX idx_task_comments_parent_id ON task_comments (parent_id);
CREATE INDEX idx_task_comments_root_id ON task_comments (root_id);

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

-- Codex 派发：服务端仅保存 Runner/仓库身份，不保存本地路径或 Codex 凭据。
CREATE TABLE IF NOT EXISTS codex_runners (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    name         VARCHAR(128) NOT NULL,
    token_hash   VARCHAR(128) NOT NULL UNIQUE,
    status       VARCHAR(16)  NOT NULL DEFAULT 'active',
    last_seen_at DATETIME     DEFAULT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at   DATETIME     DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_codex_runners_user_status ON codex_runners (user_id, status);

CREATE TABLE IF NOT EXISTS codex_runner_enrollment_codes (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    code_hash   VARCHAR(128) NOT NULL UNIQUE,
    expires_at  DATETIME     NOT NULL,
    consumed_at DATETIME     DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS codex_repositories (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    runner_id       BIGINT       NOT NULL,
    repository_key  VARCHAR(128) NOT NULL,
    display_name    VARCHAR(256) NOT NULL,
    remote_identity VARCHAR(512) NOT NULL,
    default_branch  VARCHAR(128) NOT NULL,
    last_seen_at    DATETIME     NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_codex_repositories_runner_key (runner_id, repository_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_codex_bindings (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    runner_id     BIGINT       NOT NULL,
    repository_id BIGINT       NOT NULL,
    base_branch   VARCHAR(128) NOT NULL,
    created_by    BIGINT       NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_codex_bindings_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS codex_runs (
    id                  VARCHAR(36)  NOT NULL PRIMARY KEY,
    client_request_id   VARCHAR(64)  NOT NULL,
    task_id             BIGINT       NOT NULL,
    task_key            VARCHAR(32)  NOT NULL,
    task_updated_at     DATETIME     NOT NULL,
    task_snapshot       JSON         NOT NULL,
    dispatch_instruction TEXT        NOT NULL,
    created_by          BIGINT       NOT NULL,
    runner_id           BIGINT       NOT NULL,
    repository_id       BIGINT       NOT NULL,
    base_branch         VARCHAR(128) NOT NULL,
    branch_name         VARCHAR(160) NOT NULL,
    codex_thread_id     VARCHAR(128) DEFAULT NULL,
    status              VARCHAR(16)  NOT NULL,
    lease_expires_at    DATETIME     DEFAULT NULL,
    cancel_requested_at DATETIME     DEFAULT NULL,
    result_summary      TEXT         DEFAULT NULL,
    result_payload      JSON         DEFAULT NULL,
    error_code          VARCHAR(64)  DEFAULT NULL,
    error_message       TEXT         DEFAULT NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at          DATETIME     DEFAULT NULL,
    started_at          DATETIME     DEFAULT NULL,
    finished_at         DATETIME     DEFAULT NULL,
    UNIQUE KEY uk_codex_runs_creator_request (created_by, client_request_id),
    UNIQUE KEY uk_codex_runs_thread (codex_thread_id),
    KEY idx_codex_runs_task_status (task_id, status),
    KEY idx_codex_runs_runner_status_created (runner_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS codex_run_events (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id        VARCHAR(36) NOT NULL,
    sequence_no   BIGINT      NOT NULL,
    event_type    VARCHAR(32) NOT NULL,
    event_payload JSON        NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_codex_run_events_sequence (run_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS codex_run_messages (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id         VARCHAR(36) NOT NULL,
    sender_user_id BIGINT      NOT NULL,
    content        TEXT        NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'pending',
    claimed_at     DATETIME    DEFAULT NULL,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at    DATETIME    DEFAULT NULL,
    KEY idx_codex_run_messages_run_status_created (run_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========== 种子数据（可选；密码字段为 BCrypt 哈希）==========

INSERT INTO users (username, email, password, avatar_url, user_type) VALUES
    ('admin',  'admin@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',  NULL, 'human'),
    ('user1',  'user1@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',   NULL, 'human'),
    ('user2',  'user2@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',   NULL, 'human'),
    ('alice',  'alice@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',  NULL, 'human'),
    ('bob',    'bob@example.com',    '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',    NULL, 'human'),
    ('Codex',  'codex-system@linear-lite.invalid', 'LOGIN_DISABLED', NULL, 'codex')
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    email = VALUES(email),
    user_type = VALUES(user_type);

-- 已有 binding 回填：只有系统中恰好一个 Codex 身份时才建立真实项目成员关系。
INSERT INTO project_members (project_id, user_id, role, sort_order)
SELECT binding.project_id, codex.id, 'member', 0
FROM project_codex_bindings binding
CROSS JOIN (
    SELECT MAX(id) AS id
    FROM users
    WHERE user_type = 'codex'
    HAVING COUNT(*) = 1
) codex
WHERE 1 = 1
ON DUPLICATE KEY UPDATE role = VALUES(role);

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

-- 项目邮件偏好（项目维度场景开关）
CREATE TABLE IF NOT EXISTS project_email_preferences (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    scenario_key  VARCHAR(32)  NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_email_preferences_project_scenario (project_id, scenario_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_email_preferences_project ON project_email_preferences (project_id);

-- 用户邮件发送记录（按用户、场景、业务日幂等）
CREATE TABLE IF NOT EXISTS project_email_dispatches (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scenario_key       VARCHAR(32)  NOT NULL,
    business_date      DATE         NOT NULL,
    recipient_user_id  BIGINT       NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    subject            VARCHAR(255) NOT NULL,
    task_count         INT          NOT NULL,
    last_error         VARCHAR(1024) DEFAULT NULL,
    sent_at            DATETIME     DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_email_dispatches_user_key (scenario_key, business_date, recipient_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 已有库增量：发送记录从项目维度迁移到用户维度，保证同一用户每天每场景只发送一次。
-- 旧模型可能为同一用户写入多个项目发送记录；先合并任务数，再保留最早记录作为唯一审计记录。
UPDATE project_email_dispatches keeper
JOIN (
    SELECT MIN(id) AS keeper_id, SUM(task_count) AS aggregated_task_count
    FROM project_email_dispatches
    GROUP BY scenario_key, business_date, recipient_user_id
    HAVING COUNT(*) > 1
) duplicates ON duplicates.keeper_id = keeper.id
SET keeper.task_count = duplicates.aggregated_task_count;

DELETE duplicate
FROM project_email_dispatches duplicate
JOIN project_email_dispatches keeper
  ON keeper.scenario_key = duplicate.scenario_key
 AND keeper.business_date = duplicate.business_date
 AND keeper.recipient_user_id = duplicate.recipient_user_id
 AND keeper.id < duplicate.id;

SET @dispatch_project_id_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_email_dispatches' AND COLUMN_NAME = 'project_id'
);
SET @dispatch_user_scope_ddl = IF(
    @dispatch_project_id_exists = 1,
    'ALTER TABLE project_email_dispatches DROP INDEX uk_project_email_dispatches_key, DROP INDEX idx_project_email_dispatches_project_date, DROP COLUMN project_id, ADD UNIQUE KEY uk_project_email_dispatches_user_key (scenario_key, business_date, recipient_user_id)',
    'SELECT 1'
);
PREPARE dispatch_user_scope_stmt FROM @dispatch_user_scope_ddl;
EXECUTE dispatch_user_scope_stmt;
DEALLOCATE PREPARE dispatch_user_scope_stmt;

CREATE INDEX idx_project_email_dispatches_user_date
ON project_email_dispatches (recipient_user_id, scenario_key, business_date);
