-- 已有库增量：项目邮件偏好与发送记录。
-- 执行：mysql ... < schema-v14-project-email.sql
-- 新环境请直接执行 schema.sql，无需本脚本。

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

CREATE TABLE IF NOT EXISTS project_email_dispatches (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
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
    UNIQUE KEY uk_project_email_dispatches_key (project_id, scenario_key, business_date, recipient_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_project_email_dispatches_project_date
ON project_email_dispatches (project_id, scenario_key, business_date);

-- 既有项目回填 daily_summary 默认关闭记录
INSERT INTO project_email_preferences (project_id, scenario_key, enabled)
SELECT p.id, 'daily_summary', 0
FROM projects p
WHERE NOT EXISTS (
    SELECT 1 FROM project_email_preferences pep
    WHERE pep.project_id = p.id AND pep.scenario_key = 'daily_summary'
);
