-- 已有库增量：为每个用户的项目成员关系增加侧栏排序。新环境请直接执行 schema.sql，无需本脚本。
ALTER TABLE project_members
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '当前用户侧栏中的项目顺序' AFTER role;

UPDATE project_members pm
JOIN projects p ON p.id = pm.project_id
SET pm.sort_order = p.id;

CREATE INDEX idx_project_members_user_sort ON project_members (user_id, sort_order, id);
