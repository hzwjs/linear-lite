-- LINEAR-LITE-130 / LINEAR-LITE-131：宿主授权与持久批次。
-- 来源：linear-lite-server/src/main/resources/schema.sql。
-- 通过 DBX 在目标库执行；本轮未执行持久表部署。
-- 仅新增三张表，现有业务数据保持原状。

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
