-- 只读运行收口检查。先执行 00，结合旧包基线确认相关表和字段；缺表有证据时跳过对应语句并记录。
-- 缺表不代表运行停止；缺表原因或设备状态不明时停止切换。
-- 未结束批次、结束时间缺失、未确认停止的尝试或停止状态矛盾时停止切换。
SELECT lifecycle, desired_intent, COUNT(*) AS batch_count FROM collaboration_batches GROUP BY lifecycle, desired_intent ORDER BY lifecycle, desired_intent;
SELECT COUNT(*) AS unsettled_batch_count FROM collaboration_batches WHERE lifecycle <> 'ended' OR ended_at IS NULL;
SELECT batch_id, project_id, user_id, binding_id, lifecycle, desired_intent, run_epoch, run_lease_until, ended_at FROM collaboration_batches WHERE (lifecycle <> 'ended' OR ended_at IS NULL) AND batch_id > '' ORDER BY batch_id LIMIT 1000;
-- 超过一页时，将上页最后 batch_id 填入上一条的边界并读取下一页，直至数量与计数一致。
SELECT status, COUNT(*) AS unsettled_attempt_count FROM task_execution_attempts WHERE stop_confirmed_at IS NULL OR status IN ('running', 'stop_requested') GROUP BY status;
SELECT COUNT(*) AS unsettled_attempt_count FROM task_execution_attempts WHERE stop_confirmed_at IS NULL OR status IN ('running', 'stop_requested');
SELECT id, task_id, binding_id, batch_id, status, lease_until, stop_requested_at, stop_confirmed_at FROM task_execution_attempts WHERE (stop_confirmed_at IS NULL OR status IN ('running', 'stop_requested')) AND id > 0 ORDER BY id LIMIT 1000;
-- 超过一页时，将上页最后 id 填入上一条的边界并读取下一页，直至数量与计数一致。

-- 已有表逐条记录行数；缺表跳过。不可将行数视为完整备份。
SELECT 'tasks' AS table_name, COUNT(*) AS row_count FROM tasks;
SELECT 'project_device_bindings' AS table_name, COUNT(*) AS row_count FROM project_device_bindings;
SELECT 'task_dependencies' AS table_name, COUNT(*) AS row_count FROM task_dependencies;
SELECT 'task_execution_attempts' AS table_name, COUNT(*) AS row_count FROM task_execution_attempts;
SELECT 'task_execution_operations' AS table_name, COUNT(*) AS row_count FROM task_execution_operations;
SELECT 'host_device_authorizations' AS table_name, COUNT(*) AS row_count FROM host_device_authorizations;
SELECT 'collaboration_batches' AS table_name, COUNT(*) AS row_count FROM collaboration_batches;
SELECT 'collaboration_batch_tasks' AS table_name, COUNT(*) AS row_count FROM collaboration_batch_tasks;
SELECT 'oauth2_registered_client' AS table_name, COUNT(*) AS row_count FROM oauth2_registered_client;
SELECT 'oauth2_authorization' AS table_name, COUNT(*) AS row_count FROM oauth2_authorization;
SELECT 'oauth2_authorization_consent' AS table_name, COUNT(*) AS row_count FROM oauth2_authorization_consent;
SELECT 'mcp_oauth_refresh_token_family' AS table_name, COUNT(*) AS row_count FROM mcp_oauth_refresh_token_family;
SELECT 'mcp_oauth_refresh_token' AS table_name, COUNT(*) AS row_count FROM mcp_oauth_refresh_token;
