ALTER TABLE users
    ADD COLUMN email VARCHAR(255) NULL AFTER username;

UPDATE users
SET email = CONCAT(username, '@example.com')
WHERE email IS NULL OR email = '';

ALTER TABLE users
    MODIFY COLUMN email VARCHAR(255) NOT NULL,
    ADD UNIQUE KEY uk_users_email (email);

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
