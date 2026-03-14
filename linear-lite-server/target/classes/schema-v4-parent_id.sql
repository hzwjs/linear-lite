-- Phase 7: 为 tasks 表增加 parent_id（父子任务）。在已有 linear_lite 库上执行。
-- 执行前请备份。若 schema.sql 已包含 parent_id 则无需执行。

USE linear_lite;

-- 若列已存在请勿重复执行。本项目不使用外键，关联由应用层维护。
ALTER TABLE tasks
    ADD COLUMN parent_id BIGINT NULL COMMENT '父任务 ID，NULL 表示顶层任务' AFTER project_id;

CREATE INDEX idx_tasks_parent_id ON tasks (parent_id);
