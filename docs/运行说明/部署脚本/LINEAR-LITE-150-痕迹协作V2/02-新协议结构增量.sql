-- LINEAR-LITE-150 新协议结构增量。来源为当前 schema.sql 的原文片段。
-- 本文件尚未执行。执行前使用 DBX 固定同一 MySQL 数据库会话。
-- 需先完成 00 结构预检、01 运行收口检查与停写后的可恢复备份。
-- 已存在表的定义、索引和数据先与 schema.sql 核对；发现不符须停止。
-- CREATE IF NOT EXISTS 仅处理确认缺失的表，不校验已有表的结构。
-- MySQL DDL 隐式提交，执行中断先核对实际结构，禁止盲目重跑。

-- LINEAR-LITE-114：已有任务表的协作输入字段，与全量定义保持一致。
SET @stigmergy_input_revision_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND COLUMN_NAME = 'input_revision'
);
SET @stigmergy_input_revision_ddl = IF(@stigmergy_input_revision_exists = 0,
    'ALTER TABLE tasks ADD COLUMN input_revision BIGINT NOT NULL DEFAULT 1 COMMENT ''协作输入版本''',
    'SELECT 1');
PREPARE stigmergy_input_revision_stmt FROM @stigmergy_input_revision_ddl;
EXECUTE stigmergy_input_revision_stmt;
DEALLOCATE PREPARE stigmergy_input_revision_stmt;

-- LINEAR-LITE-130：每次用户确认的宿主 OAuth 授权保留独立身份。
CREATE TABLE IF NOT EXISTS host_device_authorizations (
    id                     BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT NOT NULL,
    device_id              VARCHAR(128) NOT NULL,
    device_name            VARCHAR(128) NOT NULL,
    oauth_authorization_id VARCHAR(100) NOT NULL,
    last_heartbeat_at      DATETIME(6) DEFAULT NULL COMMENT 'UTC',
    revoked_at             DATETIME(6) DEFAULT NULL COMMENT 'UTC',
    created_at             DATETIME(6) NOT NULL COMMENT 'UTC',
    UNIQUE KEY uk_host_device_oauth_authorization (oauth_authorization_id),
    KEY idx_host_device_active (user_id, device_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_device_bindings (
    id                BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    project_id        BIGINT NOT NULL,
    device_id         VARCHAR(128) NOT NULL COMMENT '宿主持久设备标识',
    project_directory VARCHAR(2048) NOT NULL COMMENT '本机项目根目录',
    revision          BIGINT NOT NULL DEFAULT 1 COMMENT '绑定版本，目录变更与恢复时递增',
    deleted_at        DATETIME DEFAULT NULL COMMENT '绑定删除时间，保留历史归属',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_device_binding (user_id, project_id, device_id),
    KEY idx_project_device_bindings_project (project_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- LINEAR-LITE-115：绑定版本隔离旧批次，删除记录保留尝试归属。
SET @stigmergy_binding_revision_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_device_bindings' AND COLUMN_NAME = 'revision'
);
SET @stigmergy_binding_revision_ddl = IF(@stigmergy_binding_revision_exists = 0,
    'ALTER TABLE project_device_bindings ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 COMMENT ''绑定版本，目录变更与恢复时递增''',
    'SELECT 1');
PREPARE stigmergy_binding_revision_stmt FROM @stigmergy_binding_revision_ddl;
EXECUTE stigmergy_binding_revision_stmt;
DEALLOCATE PREPARE stigmergy_binding_revision_stmt;

SET @stigmergy_binding_deleted_at_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_device_bindings' AND COLUMN_NAME = 'deleted_at'
);
SET @stigmergy_binding_deleted_at_ddl = IF(@stigmergy_binding_deleted_at_exists = 0,
    'ALTER TABLE project_device_bindings ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT ''绑定删除时间，保留历史归属''',
    'SELECT 1');
PREPARE stigmergy_binding_deleted_at_stmt FROM @stigmergy_binding_deleted_at_ddl;
EXECUTE stigmergy_binding_deleted_at_stmt;
DEALLOCATE PREPARE stigmergy_binding_deleted_at_stmt;

CREATE TABLE IF NOT EXISTS task_dependencies (
    id                   BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id              BIGINT NOT NULL,
    prerequisite_task_id BIGINT NOT NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_dependency (task_id, prerequisite_task_id),
    KEY idx_task_dependencies_prerequisite (prerequisite_task_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_execution_attempts (
    id                      BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id                 BIGINT NOT NULL,
    binding_id              BIGINT NOT NULL,
    batch_id                VARCHAR(128) NOT NULL,
    host_instance_id        VARCHAR(128) NOT NULL,
    agent_instance_id       VARCHAR(128) NOT NULL,
    claim_token_hash        CHAR(64) NOT NULL COMMENT '领取标识摘要',
    input_revision          BIGINT NOT NULL,
    prerequisite_versions   JSON NOT NULL COMMENT '前置成果版本快照',
    status                  VARCHAR(32) NOT NULL COMMENT '执行尝试状态',
    lease_until             DATETIME NOT NULL COMMENT '本次租约到期时间',
    checkpoints             JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '按序追加的执行检查点',
    result_evidence         JSON DEFAULT NULL COMMENT '成果与证据引用',
    stop_reason             VARCHAR(2048) DEFAULT NULL,
    stop_requested_at       DATETIME DEFAULT NULL,
    stop_confirmed_at       DATETIME DEFAULT NULL,
    stop_confirmation       JSON DEFAULT NULL COMMENT '宿主停止确认事实',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    finished_at             DATETIME DEFAULT NULL,
    UNIQUE KEY uk_task_execution_attempt_claim (claim_token_hash),
    KEY idx_task_execution_attempt_task (task_id, status, id),
    KEY idx_task_execution_attempt_binding (binding_id, status, id),
    KEY idx_task_execution_attempt_batch (batch_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 117/118 增量：执行操作回执，用于请求幂等回放，不承载当前任务状态。
CREATE TABLE IF NOT EXISTS task_execution_operations (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    operation_id    VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    kind            VARCHAR(32) NOT NULL,
    attempt_id      BIGINT DEFAULT NULL,
    request_json    JSON NOT NULL COMMENT '排除授权材料的规范业务请求',
    response_json   JSON NOT NULL COMMENT '排除授权材料的原操作结果',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_execution_operation (project_id, user_id, operation_id),
    KEY idx_task_execution_operation_attempt (attempt_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- LINEAR-LITE-131：批次控制意图与宿主运行归属；任务事实来自原任务与执行记录。
CREATE TABLE IF NOT EXISTS collaboration_batches (
    batch_id              VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    project_id            BIGINT NOT NULL,
    user_id               BIGINT NOT NULL,
    binding_id            BIGINT NOT NULL,
    binding_revision      BIGINT NOT NULL,
    host_authorization_id BIGINT NOT NULL,
    max_slots             INT NOT NULL,
    max_duration_ms       BIGINT NOT NULL,
    max_attempts          INT NOT NULL,
    desired_intent        VARCHAR(16) NOT NULL,
    intent_revision       BIGINT NOT NULL,
    lifecycle             VARCHAR(24) NOT NULL,
    host_instance_id      VARCHAR(128) DEFAULT NULL,
    run_epoch             BIGINT NOT NULL,
    run_lease_until       DATETIME(6) DEFAULT NULL COMMENT 'UTC',
    started_at            DATETIME(6) DEFAULT NULL COMMENT 'UTC',
    deadline_at           DATETIME(6) DEFAULT NULL COMMENT 'UTC',
    ended_at              DATETIME(6) DEFAULT NULL COMMENT 'UTC',
    end_reason            VARCHAR(128) DEFAULT NULL,
    created_at            DATETIME(6) NOT NULL COMMENT 'UTC',
    KEY idx_collaboration_batches_user_project (project_id, user_id, created_at, batch_id),
    KEY idx_collaboration_batches_grant (host_authorization_id, lifecycle, batch_id),
    KEY idx_collaboration_batches_binding (binding_id, lifecycle, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collaboration_batch_tasks (
    batch_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id  BIGINT NOT NULL,
    PRIMARY KEY (batch_id, task_id),
    KEY idx_collaboration_batch_tasks_task (task_id, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
