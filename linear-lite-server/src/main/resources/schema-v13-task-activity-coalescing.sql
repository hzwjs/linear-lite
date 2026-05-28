-- 已有库增量：降低任务描述活动记录写入频率，并补齐活动查询/合并索引。
-- 执行：mysql ... < schema-v13-task-activity-coalescing.sql
-- 新环境请直接执行 schema.sql，无需本脚本。

CREATE INDEX idx_task_activities_task_created_id
ON task_activities (task_id, created_at, id);

CREATE INDEX idx_task_activities_coalesce
ON task_activities (task_id, user_id, action_type, field_name, created_at, id);

-- 压缩历史上同一任务、同一用户、同一天内的 description 更新记录。
-- 保留当天最后一条作为展示记录，并把 old_value 回写为当天第一条的 old_value。
UPDATE task_activities keep_row
JOIN (
    SELECT
        task_id,
        user_id,
        DATE(created_at) AS activity_date,
        MIN(id) AS first_id,
        MAX(id) AS last_id
    FROM task_activities
    WHERE action_type = 'changed'
      AND field_name = 'description'
    GROUP BY task_id, user_id, DATE(created_at)
    HAVING COUNT(*) > 1
) duplicate_group ON keep_row.id = duplicate_group.last_id
JOIN task_activities first_row ON first_row.id = duplicate_group.first_id
SET keep_row.old_value = first_row.old_value;

DELETE activity_row
FROM task_activities activity_row
JOIN (
    SELECT
        task_id,
        user_id,
        DATE(created_at) AS activity_date,
        MAX(id) AS keep_id
    FROM task_activities
    WHERE action_type = 'changed'
      AND field_name = 'description'
    GROUP BY task_id, user_id, DATE(created_at)
    HAVING COUNT(*) > 1
) duplicate_group ON duplicate_group.task_id = activity_row.task_id
    AND duplicate_group.user_id = activity_row.user_id
    AND duplicate_group.activity_date = DATE(activity_row.created_at)
WHERE activity_row.action_type = 'changed'
  AND activity_row.field_name = 'description'
  AND activity_row.id <> duplicate_group.keep_id;
