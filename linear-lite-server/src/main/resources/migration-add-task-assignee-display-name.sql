-- 已有库增量：为 tasks 增加处理人展示名。新环境请直接执行 schema.sql，无需本脚本。
ALTER TABLE tasks
    ADD COLUMN assignee_display_name VARCHAR(128) DEFAULT NULL COMMENT '导入或外部处理人展示名，无系统用户时使用' AFTER assignee_id;
