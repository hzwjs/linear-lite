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

-- 项目可配置多个 GitLab 仓库；每个仓库 URL 与 Webhook Token 仅绑定一个 Linear Lite 项目。
CREATE TABLE IF NOT EXISTS project_gitlab_repositories (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id         BIGINT       NOT NULL COMMENT '逻辑关联 projects.id',
    repository_url     VARCHAR(512) NOT NULL COMMENT 'GitLab 项目 Web URL，作为固定仓库身份',
    repository_path    VARCHAR(512) NOT NULL COMMENT 'GitLab path_with_namespace',
    webhook_token_hash VARCHAR(128) NOT NULL COMMENT 'X-Gitlab-Token 的 SHA-256 哈希',
    created_by         BIGINT       NOT NULL COMMENT '配置仓库的项目创建者，作为同步评论作者',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_gitlab_repositories_url (repository_url),
    UNIQUE KEY uk_project_gitlab_repositories_token (webhook_token_hash),
    KEY idx_project_gitlab_repositories_project (project_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 项目可配置多个 GitHub 仓库；webhook_secret 仅服务端使用，创建/重置时返回一次。
CREATE TABLE IF NOT EXISTS project_github_repositories (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT       NOT NULL,
    repository_url  VARCHAR(512) NOT NULL,
    repository_path VARCHAR(512) NOT NULL,
    webhook_secret  VARCHAR(128) NOT NULL COMMENT 'AES-GCM 密文，密钥由 JWT_SECRET 派生',
    created_by      BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_github_repositories_url (repository_url),
    KEY idx_project_github_repositories_project (project_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- 项目文档：正文固定保存 BlockNote JSON 数组，树关系只由 parent_document_id 表达。
CREATE TABLE IF NOT EXISTS project_documents (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
    parent_document_id BIGINT       DEFAULT NULL,
    external_source    VARCHAR(64)  DEFAULT NULL COMMENT '外部迁移来源',
    external_source_id VARCHAR(128) DEFAULT NULL COMMENT '外部来源内稳定文档 ID',
    title              VARCHAR(256) NOT NULL,
    content_json       LONGTEXT     NOT NULL,
    sort_order         INT          NOT NULL DEFAULT 0,
    version            BIGINT       NOT NULL DEFAULT 1,
    creator_id         BIGINT       NOT NULL,
    last_editor_id     BIGINT       NOT NULL,
    archived_at        DATETIME     DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project_documents_project_parent_order (project_id, parent_document_id, sort_order, id),
    INDEX idx_project_documents_parent (parent_document_id),
    INDEX idx_project_documents_project_updated (project_id, updated_at, id),
    UNIQUE KEY uk_project_documents_external_source (project_id, external_source, external_source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_document_revisions (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id   BIGINT       NOT NULL,
    version       BIGINT       NOT NULL,
    title         VARCHAR(256) NOT NULL,
    content_json  LONGTEXT     NOT NULL,
    editor_id     BIGINT       NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_document_revisions_document_version (document_id, version),
    INDEX idx_project_document_revisions_document_created (document_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_document_attachments (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id   BIGINT       NOT NULL COMMENT '逻辑关联 projects.id',
    document_id  BIGINT       NOT NULL COMMENT '逻辑关联 project_documents.id',
    source_id    VARCHAR(512) DEFAULT NULL COMMENT '外部迁移来源内的稳定附件标识',
    object_key   VARCHAR(512) NOT NULL,
    file_name    VARCHAR(256) NOT NULL,
    file_size    BIGINT       NOT NULL,
    content_type VARCHAR(128) DEFAULT NULL,
    sha256       CHAR(64)     NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_document_attachments_source (document_id, source_id),
    INDEX idx_project_document_attachments_project (project_id, id),
    INDEX idx_project_document_attachments_document (document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文档收藏按用户独立存储，只允许一条用户-文档关系。
CREATE TABLE IF NOT EXISTS project_document_favorites (
    id          BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT   NOT NULL,
    document_id BIGINT   NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_document_favorites_user_document (user_id, document_id),
    INDEX idx_project_document_favorites_document_id (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- 项目内容统一语义索引队列：代次防止旧 Worker 覆盖新变更，租约防止多实例重复领取。
CREATE TABLE IF NOT EXISTS project_content_semantic_index_jobs (
    content_type VARCHAR(16) NOT NULL,
    resource_id  BIGINT      NOT NULL,
    operation    VARCHAR(16) NOT NULL,
    generation   BIGINT      NOT NULL DEFAULT 1,
    run_after    DATETIME    NOT NULL,
    attempts     INT         NOT NULL DEFAULT 0,
    lease_until  DATETIME    DEFAULT NULL,
    PRIMARY KEY (content_type, resource_id),
    INDEX idx_project_content_semantic_jobs_due (run_after, lease_until)
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
    source_type VARCHAR(32)  DEFAULT NULL COMMENT '外部来源类型，如 gitlab_commit；人工评论为 NULL',
    external_ref VARCHAR(128) DEFAULT NULL COMMENT '外部来源唯一标识，如 repositoryId:commitSha',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_task_comments_task_id ON task_comments (task_id, created_at, id);
CREATE INDEX idx_task_comments_parent_id ON task_comments (parent_id);
CREATE INDEX idx_task_comments_root_id ON task_comments (root_id);
CREATE UNIQUE INDEX uk_task_comments_source_ref ON task_comments (source_type, external_ref, task_id);

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

-- ========== 种子数据（可选；密码字段为 BCrypt 哈希）==========

INSERT INTO users (username, email, password, avatar_url) VALUES
    ('admin',  'admin@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',  NULL),
    ('user1',  'user1@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',   NULL),
    ('user2',  'user2@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',   NULL),
    ('alice',  'alice@example.com',  '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',  NULL),
    ('bob',    'bob@example.com',    '$2y$10$bMfmFFEWAOwDerIh/eQMruD0GYHrFkSieDd7cHDV07RnB8dtR545u',    NULL)
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

-- ========== 归档：GitLab 多仓库任务评论联动（幂等增量）==========
-- 已有库增量 1：评论来源字段支持按“仓库 + commit SHA”去重；人工评论仍保持 NULL。
SET @task_comments_source_type_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_comments' AND COLUMN_NAME = 'source_type'
);
SET @task_comments_source_type_ddl = IF(
    @task_comments_source_type_exists = 0,
    'ALTER TABLE task_comments ADD COLUMN source_type VARCHAR(32) DEFAULT NULL COMMENT ''外部来源类型，如 gitlab_commit；人工评论为 NULL'' AFTER depth, ADD COLUMN external_ref VARCHAR(128) DEFAULT NULL COMMENT ''外部来源唯一标识，如 repositoryId:commitSha'' AFTER source_type',
    'SELECT 1'
);
PREPARE task_comments_source_type_stmt FROM @task_comments_source_type_ddl;
EXECUTE task_comments_source_type_stmt;
DEALLOCATE PREPARE task_comments_source_type_stmt;

SET @task_comments_external_ref_length = (
    SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_comments' AND COLUMN_NAME = 'external_ref'
);
SET @task_comments_external_ref_ddl = IF(
    @task_comments_external_ref_length < 128,
    'ALTER TABLE task_comments MODIFY COLUMN external_ref VARCHAR(128) DEFAULT NULL COMMENT ''外部来源唯一标识，如 repositoryId:commitSha''',
    'SELECT 1'
);
PREPARE task_comments_external_ref_stmt FROM @task_comments_external_ref_ddl;
EXECUTE task_comments_external_ref_stmt;
DEALLOCATE PREPARE task_comments_external_ref_stmt;

SET @task_comments_source_ref_index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_comments' AND INDEX_NAME = 'uk_task_comments_source_ref'
);
SET @task_comments_source_ref_index_ddl = IF(
    @task_comments_source_ref_index_exists = 0,
    'ALTER TABLE task_comments ADD UNIQUE KEY uk_task_comments_source_ref (source_type, external_ref, task_id)',
    'SELECT 1'
);
PREPARE task_comments_source_ref_index_stmt FROM @task_comments_source_ref_index_ddl;
EXECUTE task_comments_source_ref_index_stmt;
DEALLOCATE PREPARE task_comments_source_ref_index_stmt;
