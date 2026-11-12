-- 部署后结构与数据核对；使用 DBX 执行。
SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='tasks'
AND COLUMN_NAME IN ('agent_execution_enabled','input_revision','write_scopes','public_context_refs'))
OR TABLE_NAME IN ('project_device_bindings','task_dependencies','task_execution_attempts','task_execution_operations'))
ORDER BY TABLE_NAME,ORDINAL_POSITION;
SELECT COUNT(*) AS task_count,MAX(id) AS last_task_id,MAX(updated_at) AS last_updated_at FROM tasks;
SELECT COUNT(*) AS nondefault_existing_tasks FROM tasks WHERE agent_execution_enabled<>FALSE OR input_revision<>1
OR JSON_LENGTH(write_scopes)<>0 OR JSON_LENGTH(public_context_refs)<>0;
SELECT 'project_device_bindings' AS table_name,COUNT(*) AS row_count FROM project_device_bindings
UNION ALL SELECT 'task_dependencies',COUNT(*) FROM task_dependencies
UNION ALL SELECT 'task_execution_attempts',COUNT(*) FROM task_execution_attempts
UNION ALL SELECT 'task_execution_operations',COUNT(*) FROM task_execution_operations;
