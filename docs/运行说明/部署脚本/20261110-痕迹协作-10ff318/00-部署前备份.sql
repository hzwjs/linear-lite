-- 使用 DBX 对 linear-lite 库执行；本轮执行完成，备份共 2816 条。
-- 执行前确认备份表名未被占用；不用于重复执行。
CREATE TABLE tasks_bak_stigmergy_20261110_10ff318 LIKE tasks;
INSERT INTO tasks_bak_stigmergy_20261110_10ff318 SELECT * FROM tasks;
SELECT COUNT(*) AS backup_count,MAX(id) AS last_task_id,MAX(updated_at) AS last_updated_at
FROM tasks_bak_stigmergy_20261110_10ff318;
