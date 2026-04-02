-- =============================================================================
-- 将「仅有 assignee_display_name、无 assignee_id」的任务，按展示名与 users.username
-- 精确匹配（TRIM 后相等）回填 assignee_id，并清空 assignee_display_name。
--
-- 适用：CSV/导入时负责人只有文本，后来同名用户已在系统注册且 username 与导入名一致。
--
-- 使用前：
--   1. 备份数据库。
--   2. 先执行下方「预览」SELECT，确认匹配行符合预期。
--   3. 默认要求该用户已是任务所在项目的 project_members（更安全）。
--
-- 执行示例（MySQL 8）：
--   mysql -u USER -p linear_lite < scripts/backfill-assignee-from-display-name.sql
--
-- 若展示名与注册 username 不一致（如导入「张三」、账号 zhangsan），本脚本不会匹配，
-- 需单独改数据或手写 UPDATE。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 预览 A：将参与回填的行（仅匹配到且已是项目成员）
-- -----------------------------------------------------------------------------
SELECT
    t.id,
    t.task_key,
    t.project_id,
    t.assignee_display_name,
    u.id AS matched_user_id,
    u.username
FROM tasks t
INNER JOIN users u ON TRIM(t.assignee_display_name) = u.username
INNER JOIN project_members pm ON pm.project_id = t.project_id AND pm.user_id = u.id
WHERE t.assignee_id IS NULL
  AND TRIM(COALESCE(t.assignee_display_name, '')) <> '';

-- -----------------------------------------------------------------------------
-- 预览 B：能匹配到用户、但该用户不在该项目成员里（不会默认更新，需先加成员或改用下方「无成员校验」）
-- -----------------------------------------------------------------------------
SELECT
    t.id,
    t.task_key,
    t.project_id,
    t.assignee_display_name,
    u.id AS matched_user_id,
    u.username
FROM tasks t
INNER JOIN users u ON TRIM(t.assignee_display_name) = u.username
LEFT JOIN project_members pm ON pm.project_id = t.project_id AND pm.user_id = u.id
WHERE t.assignee_id IS NULL
  AND TRIM(COALESCE(t.assignee_display_name, '')) <> ''
  AND pm.user_id IS NULL;

-- -----------------------------------------------------------------------------
-- 正式更新（要求 project_members，推荐）
-- -----------------------------------------------------------------------------
START TRANSACTION;

UPDATE tasks t
INNER JOIN users u ON TRIM(t.assignee_display_name) = u.username
INNER JOIN project_members pm ON pm.project_id = t.project_id AND pm.user_id = u.id
SET
    t.assignee_id = u.id,
    t.assignee_display_name = NULL
WHERE t.assignee_id IS NULL
  AND TRIM(COALESCE(t.assignee_display_name, '')) <> '';

-- 查看本连接上一语句影响行数（MySQL 客户端）
SELECT ROW_COUNT() AS updated_rows;

COMMIT;

-- =============================================================================
-- 可选：不校验项目成员（单团队/确信所有用户均可指派时使用）
-- 使用前请注释掉上面整段 START TRANSACTION … COMMIT，再取消注释下面块单独执行。
-- =============================================================================
--
-- START TRANSACTION;
--
-- UPDATE tasks t
-- INNER JOIN users u ON TRIM(t.assignee_display_name) = u.username
-- SET
--     t.assignee_id = u.id,
--     t.assignee_display_name = NULL
-- WHERE t.assignee_id IS NULL
--   AND TRIM(COALESCE(t.assignee_display_name, '')) <> '';
--
-- SELECT ROW_COUNT() AS updated_rows;
--
-- COMMIT;
