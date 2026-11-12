-- 只读切换后检查：DBX 逐条执行，结果不符合要求时停止切换。
SELECT DATABASE() AS selected_database, VERSION() AS mysql_version;
-- 以下四个旧字段必须全部不存在，结果须为 0。
SELECT COUNT(*) AS obsolete_column_count FROM information_schema.COLUMNS
WHERE table_schema = DATABASE() AND ((table_name = 'tasks' AND column_name IN ('write_scopes','agent_execution_enabled','public_context_refs')) OR (table_name = 'task_execution_attempts' AND column_name = 'held_write_scopes'));
-- 以下关键字段必须全部存在，结果须为 0。
WITH required_columns AS (
  SELECT 'tasks' AS table_name, 'input_revision' AS column_name
  UNION ALL SELECT 'project_device_bindings', 'revision'
  UNION ALL SELECT 'project_device_bindings', 'deleted_at'
  UNION ALL SELECT 'task_execution_attempts', 'stop_confirmed_at'
  UNION ALL SELECT 'collaboration_batches', 'lifecycle'
  UNION ALL SELECT 'host_device_authorizations', 'oauth_authorization_id'
)
SELECT COUNT(*) AS missing_column_count FROM required_columns r LEFT JOIN information_schema.COLUMNS c
ON c.table_schema = DATABASE() AND c.table_name = r.table_name AND c.column_name = r.column_name WHERE c.column_name IS NULL;
-- 重跑 00 的表、字段及索引查询并与当前 schema.sql 逐表比较；重跑 01 与切换前行数比较。
