-- 任务分配通知不关联评论；对应增量已归档至 schema.sql。
ALTER TABLE in_app_notifications
    MODIFY COLUMN comment_id BIGINT DEFAULT NULL COMMENT '评论提及关联评论，任务分配为空';
