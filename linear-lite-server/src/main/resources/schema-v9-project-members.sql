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

INSERT INTO project_members (project_id, user_id, role)
SELECT id, creator_id, 'owner'
FROM projects
ON DUPLICATE KEY UPDATE role = VALUES(role);
