-- 项目文档附件：为 Outline 等外部迁移提供可鉴权、可校验、可幂等的文件存储。
ALTER TABLE project_documents
    ADD COLUMN external_source VARCHAR(64) DEFAULT NULL COMMENT '外部迁移来源' AFTER parent_document_id,
    ADD COLUMN external_source_id VARCHAR(128) DEFAULT NULL COMMENT '外部来源内稳定文档 ID' AFTER external_source,
    ADD UNIQUE KEY uk_project_documents_external_source (project_id, external_source, external_source_id);

CREATE TABLE IF NOT EXISTS project_document_attachments (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id   BIGINT       NOT NULL COMMENT '逻辑关联 projects.id',
    document_id  BIGINT       NOT NULL COMMENT '逻辑关联 project_documents.id',
    source_id    VARCHAR(512) DEFAULT NULL COMMENT '外部迁移来源内的稳定附件标识',
    object_key   VARCHAR(512) NOT NULL,
    file_name    VARCHAR(256) NOT NULL,
    file_size    BIGINT       NOT NULL,
    content_type VARCHAR(128) DEFAULT NULL,
    sha256       CHAR(64)     NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_document_attachments_source (document_id, source_id),
    INDEX idx_project_document_attachments_project (project_id, id),
    INDEX idx_project_document_attachments_document (document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
