-- 已有库增量：删除已废弃的企业微信成员 UserID 字段。
-- 新环境请直接执行 schema.sql，无需本脚本。

SET @users_wecom_user_id_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'wecom_user_id'
);

SET @users_wecom_user_id_ddl = IF(
    @users_wecom_user_id_exists = 1,
    'ALTER TABLE users DROP COLUMN wecom_user_id',
    'SELECT 1'
);

PREPARE users_wecom_user_id_stmt FROM @users_wecom_user_id_ddl;
EXECUTE users_wecom_user_id_stmt;
DEALLOCATE PREPARE users_wecom_user_id_stmt;
