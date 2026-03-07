-- Phase 3: 任务时间字段。在已有 linear_lite 库上执行，与 schema.sql 中的 tasks 表兼容。
-- 执行前请确认已存在 tasks 表。

USE linear_lite;

ALTER TABLE tasks
    ADD COLUMN due_date DATETIME NULL COMMENT '预计完成/截止日期' AFTER assignee_id,
    ADD COLUMN completed_at DATETIME NULL COMMENT '实际完成时间，终态时由系统写入' AFTER due_date;
