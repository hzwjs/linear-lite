-- 只读预检：在 DBX 已选定的目标库中逐条执行，记录结果并对照当前 schema.sql。
-- 每条结果核对行数与截断提示；存在表的列及索引必须完整读取。
SELECT DATABASE() AS selected_database, VERSION() AS mysql_version;

WITH expected AS (
SELECT 'tasks' AS table_name UNION ALL
SELECT 'project_device_bindings' AS table_name UNION ALL
SELECT 'task_dependencies' AS table_name UNION ALL
SELECT 'task_execution_attempts' AS table_name UNION ALL
SELECT 'task_execution_operations' AS table_name UNION ALL
SELECT 'host_device_authorizations' AS table_name UNION ALL
SELECT 'collaboration_batches' AS table_name UNION ALL
SELECT 'collaboration_batch_tasks' AS table_name UNION ALL
SELECT 'oauth2_registered_client' AS table_name UNION ALL
SELECT 'oauth2_authorization' AS table_name UNION ALL
SELECT 'oauth2_authorization_consent' AS table_name UNION ALL
SELECT 'mcp_oauth_refresh_token_family' AS table_name UNION ALL
SELECT 'mcp_oauth_refresh_token' AS table_name
)
SELECT e.table_name, t.table_type, t.engine, t.table_collation
FROM expected e LEFT JOIN information_schema.TABLES t
  ON t.table_schema = DATABASE() AND t.table_name = e.table_name
ORDER BY e.table_name;

-- tasks：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'tasks' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'tasks' ORDER BY index_name, seq_in_index;

-- project_device_bindings：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'project_device_bindings' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'project_device_bindings' ORDER BY index_name, seq_in_index;

-- task_dependencies：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'task_dependencies' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'task_dependencies' ORDER BY index_name, seq_in_index;

-- task_execution_attempts：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'task_execution_attempts' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'task_execution_attempts' ORDER BY index_name, seq_in_index;

-- task_execution_operations：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'task_execution_operations' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'task_execution_operations' ORDER BY index_name, seq_in_index;

-- host_device_authorizations：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'host_device_authorizations' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'host_device_authorizations' ORDER BY index_name, seq_in_index;

-- collaboration_batches：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'collaboration_batches' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'collaboration_batches' ORDER BY index_name, seq_in_index;

-- collaboration_batch_tasks：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'collaboration_batch_tasks' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'collaboration_batch_tasks' ORDER BY index_name, seq_in_index;

-- oauth2_registered_client：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'oauth2_registered_client' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'oauth2_registered_client' ORDER BY index_name, seq_in_index;

-- oauth2_authorization：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'oauth2_authorization' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'oauth2_authorization' ORDER BY index_name, seq_in_index;

-- oauth2_authorization_consent：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'oauth2_authorization_consent' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'oauth2_authorization_consent' ORDER BY index_name, seq_in_index;

-- mcp_oauth_refresh_token_family：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'mcp_oauth_refresh_token_family' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'mcp_oauth_refresh_token_family' ORDER BY index_name, seq_in_index;

-- mcp_oauth_refresh_token：缺表时两项结果为空；已有表需核对字段定义及索引。
SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name, column_comment FROM information_schema.COLUMNS WHERE table_schema = DATABASE() AND table_name = 'mcp_oauth_refresh_token' ORDER BY ordinal_position;
SELECT index_name, non_unique, seq_in_index, column_name, sub_part, index_type FROM information_schema.STATISTICS WHERE table_schema = DATABASE() AND table_name = 'mcp_oauth_refresh_token' ORDER BY index_name, seq_in_index;
