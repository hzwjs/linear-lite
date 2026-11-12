-- LINEAR-LITE-139，部署前备份任务与执行事实，确认旧批次收口。
-- 此脚本使用 DBX 执行；未在本轮执行。
-- LINEAR-LITE-139：备份旧字段与执行事实后，移除任务级范围和预配置授权。
SET @v2_drop_write_scopes_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND COLUMN_NAME = 'write_scopes') > 0,
    'ALTER TABLE tasks DROP COLUMN write_scopes',
    'SELECT 1');
PREPARE v2_drop_write_scopes_stmt FROM @v2_drop_write_scopes_ddl;
EXECUTE v2_drop_write_scopes_stmt;
DEALLOCATE PREPARE v2_drop_write_scopes_stmt;

SET @v2_drop_agent_execution_enabled_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND COLUMN_NAME = 'agent_execution_enabled') > 0,
    'ALTER TABLE tasks DROP COLUMN agent_execution_enabled',
    'SELECT 1');
PREPARE v2_drop_agent_execution_enabled_stmt FROM @v2_drop_agent_execution_enabled_ddl;
EXECUTE v2_drop_agent_execution_enabled_stmt;
DEALLOCATE PREPARE v2_drop_agent_execution_enabled_stmt;

SET @v2_drop_public_context_refs_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tasks' AND COLUMN_NAME = 'public_context_refs') > 0,
    'ALTER TABLE tasks DROP COLUMN public_context_refs',
    'SELECT 1');
PREPARE v2_drop_public_context_refs_stmt FROM @v2_drop_public_context_refs_ddl;
EXECUTE v2_drop_public_context_refs_stmt;
DEALLOCATE PREPARE v2_drop_public_context_refs_stmt;

SET @v2_drop_held_write_scopes_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_execution_attempts' AND COLUMN_NAME = 'held_write_scopes') > 0,
    'ALTER TABLE task_execution_attempts DROP COLUMN held_write_scopes',
    'SELECT 1');
PREPARE v2_drop_held_write_scopes_stmt FROM @v2_drop_held_write_scopes_ddl;
EXECUTE v2_drop_held_write_scopes_stmt;
DEALLOCATE PREPARE v2_drop_held_write_scopes_stmt;
