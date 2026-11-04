-- 远程终端团队版：已有库增量（全新库执行 schema.sql 即可，语句均为幂等 CREATE TABLE IF NOT EXISTS）。
-- 项目 CA：私钥经 AES-256-GCM 加密后入库（加密密钥来自环境变量 RT_CA_KEK）；轮换期间新旧 CA 同时有效。
CREATE TABLE IF NOT EXISTS rt_ca_keys (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id       BIGINT        NOT NULL COMMENT '逻辑关联 projects.id',
    public_key       VARCHAR(512)  NOT NULL COMMENT 'OpenSSH ssh-ed25519 公钥文本',
    private_key_enc  VARBINARY(512) NOT NULL COMMENT 'nonce(12) + AES-256-GCM(seed) 密文，AAD 绑定项目',
    status           VARCHAR(16)   NOT NULL COMMENT 'active | retiring | retired',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rt_ca_project_status (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rt_targets (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL COMMENT '逻辑关联 projects.id',
    target_id     VARCHAR(100) NOT NULL COMMENT '项目内唯一，进入证书 principal，创建后不可改',
    alias         VARCHAR(256) NOT NULL,
    environment   VARCHAR(256) NOT NULL,
    host          VARCHAR(256) NOT NULL,
    port          INT          NOT NULL,
    host_key      TEXT         NOT NULL COMMENT '经核对的 OpenSSH 主机公钥（类型 + Base64）',
    host_key_rev  INT          NOT NULL DEFAULT 1 COMMENT 'host_key 每次变更 +1',
    deleted_at    DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_rt_targets_project (project_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rt_profiles (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    target_ref       BIGINT       NOT NULL COMMENT '逻辑关联 rt_targets.id',
    profile_id       VARCHAR(100) NOT NULL,
    login_user       VARCHAR(64)  NOT NULL,
    max_ttl_seconds  INT          NOT NULL COMMENT '1–28800',
    permit_pty       TINYINT      NOT NULL DEFAULT 1,
    deleted_at       DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rt_profiles_target (target_ref, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rt_grants (
    id           BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    profile_ref  BIGINT   NOT NULL COMMENT '逻辑关联 rt_profiles.id',
    user_id      BIGINT   NOT NULL,
    granted_by   BIGINT   NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rt_grants (profile_ref, user_id),
    KEY idx_rt_grants_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 证书签发记录；id 即证书序列号。
CREATE TABLE IF NOT EXISTS rt_certificates (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id       BIGINT       NOT NULL,
    ca_ref           BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    target_ref       BIGINT       NOT NULL,
    profile_ref      BIGINT       NOT NULL,
    principal        VARCHAR(512) NOT NULL,
    key_fingerprint  VARCHAR(128) NOT NULL COMMENT '临时公钥 SHA256 指纹',
    valid_after      DATETIME     NOT NULL,
    valid_before     DATETIME     NOT NULL,
    client_ip        VARCHAR(64)  NOT NULL,
    oauth_client_id  VARCHAR(128) NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rt_cert_project_time (project_id, created_at),
    KEY idx_rt_cert_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 目标、身份、授权、CA 的变更日志；before/after 不含私钥。
CREATE TABLE IF NOT EXISTS rt_audit_events (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id   BIGINT        NOT NULL,
    actor_id     BIGINT        NOT NULL,
    action       VARCHAR(64)   NOT NULL,
    subject      VARCHAR(256)  NOT NULL,
    before_json  TEXT          NULL,
    after_json   TEXT          NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rt_audit_project_time (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
