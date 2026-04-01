-- 已有库增量：为 tasks 增加计划开始日期。新环境请直接执行 schema.sql，无需本脚本。
ALTER TABLE tasks
    ADD COLUMN planned_start_date DATETIME DEFAULT NULL COMMENT '计划开始日期' AFTER due_date;
