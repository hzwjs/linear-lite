-- ========== 归档：HTML 文档类型与正文统一字段 ==========
-- 发布时通过 DBX 执行本段；旧服务停写，字段更名后部署新服务。
SET @html_doc_old_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_documents' AND COLUMN_NAME = 'content_json');
SET @html_doc_ddl = IF(@html_doc_old_column = 1,
    'ALTER TABLE project_documents CHANGE COLUMN content_json content LONGTEXT NOT NULL, ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT ''BLOCKNOTE'' AFTER content', 'SELECT 1');
PREPARE html_doc_stmt FROM @html_doc_ddl;
EXECUTE html_doc_stmt;
DEALLOCATE PREPARE html_doc_stmt;
-- 存量正文的类型赋值完成后撤掉默认值，后续写入必须声明类型。
ALTER TABLE project_documents ALTER COLUMN content_format DROP DEFAULT;
SET @html_doc_old_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_document_revisions' AND COLUMN_NAME = 'content_json');
SET @html_doc_ddl = IF(@html_doc_old_column = 1,
    'ALTER TABLE project_document_revisions CHANGE COLUMN content_json content LONGTEXT NOT NULL, ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT ''BLOCKNOTE'' AFTER content', 'SELECT 1');
PREPARE html_doc_stmt FROM @html_doc_ddl;
EXECUTE html_doc_stmt;
DEALLOCATE PREPARE html_doc_stmt;
-- 存量正文的类型赋值完成后撤掉默认值，后续写入必须声明类型。
ALTER TABLE project_document_revisions ALTER COLUMN content_format DROP DEFAULT;
