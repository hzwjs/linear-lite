-- 项目文档是项目内唯一知识正文来源；历史版本只用于恢复，不参与当前正文读取。
CREATE TABLE IF NOT EXISTS project_documents (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id         BIGINT       NOT NULL,
    parent_document_id BIGINT       DEFAULT NULL,
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
    INDEX idx_project_documents_project_updated (project_id, updated_at, id)
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
