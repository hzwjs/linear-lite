-- 痕迹协作增量部署；来源提交 10ff318，定义归档于 schema.sql。
-- 目标数据库：linear-lite（MySQL 8.0）；2026-11-10 预检确认四列与四表均不存在。
-- 执行前核对目标库、保存旧 tasks 表结构与备份；禁止对现有库执行完整 schema.sql。
-- DDL 隐式提交；任一语句失败须检查实际结构后处理，禁止盲目重跑。
-- 本脚本不修改任务业务字段，不包含种子数据、凭据或环境配置。

ALTER TABLE tasks
    ADD COLUMN agent_execution_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否授权 Agent 领取',
    ADD COLUMN input_revision BIGINT NOT NULL DEFAULT 1 COMMENT '协作输入版本',
    ADD COLUMN write_scopes JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '计划写入的项目相对路径',
    ADD COLUMN public_context_refs JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '公共约定文档及版本引用';

CREATE TABLE project_device_bindings (
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

CREATE TABLE task_dependencies (
    id                   BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id              BIGINT NOT NULL,
    prerequisite_task_id BIGINT NOT NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_dependency (task_id, prerequisite_task_id),
    KEY idx_task_dependencies_prerequisite (prerequisite_task_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_execution_attempts (
    id                      BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id                 BIGINT NOT NULL,
    binding_id              BIGINT NOT NULL,
    batch_id                VARCHAR(128) NOT NULL,
    host_instance_id        VARCHAR(128) NOT NULL,
    agent_instance_id       VARCHAR(128) NOT NULL,
    claim_token_hash        CHAR(64) NOT NULL COMMENT '领取标识摘要',
    input_revision          BIGINT NOT NULL,
    prerequisite_versions   JSON NOT NULL COMMENT '前置成果版本快照',
    held_write_scopes       JSON NOT NULL COMMENT '本次持有的路径范围',
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

CREATE TABLE task_execution_operations (
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
