-- Linear Lite 表结构（本项目不使用外键，表间关联由应用层维护）
-- 执行前请先创建数据库：CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;

USE linear_lite;

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
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_tasks_project_id ON tasks (project_id);
CREATE INDEX idx_tasks_parent_id  ON tasks (parent_id);
CREATE INDEX idx_tasks_task_key ON tasks (task_key);
CREATE INDEX idx_tasks_creator_id  ON tasks (creator_id);
CREATE INDEX idx_tasks_assignee_id ON tasks (assignee_id);

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
