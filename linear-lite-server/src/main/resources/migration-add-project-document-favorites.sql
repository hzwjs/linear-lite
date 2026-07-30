-- 项目文档收藏：按用户持久化，并通过唯一键保证幂等收藏。
CREATE TABLE IF NOT EXISTS project_document_favorites (
    id          BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT   NOT NULL,
    document_id BIGINT   NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_document_favorites_user_document (user_id, document_id),
    INDEX idx_project_document_favorites_document_id (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
