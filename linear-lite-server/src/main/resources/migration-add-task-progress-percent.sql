-- 已有库增量：为 tasks 增加进度百分比（0–100）。新环境请直接执行 schema.sql，无需本脚本。
ALTER TABLE tasks
    ADD COLUMN progress_percent TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '完成进度 0–100' AFTER due_date;
