-- 迁移：为 tasks 表增加 task_key 列（若 Wave 1 已执行过 schema.sql 且无此列，请执行本脚本）
-- 使用前请备份。执行后需为已有任务回填 task_key（按 project_id 生成 identifier-序号），此处假设表为空故未写回填语句。

USE linear_lite;

-- MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，若已存在 task_key 请勿重复执行
ALTER TABLE tasks
    ADD COLUMN task_key VARCHAR(32) NULL UNIQUE COMMENT '带项目前缀的任务 ID，如 ENG-1' AFTER id;
