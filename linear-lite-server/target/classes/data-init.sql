-- 种子数据：预置用户与示例项目
-- 依赖 schema.sql 已执行；密码为明文占位，正式环境需改为 BCrypt 等加密

USE linear_lite;

-- 预置用户（3～5 个）
INSERT INTO users (username, password, avatar_url) VALUES
    ('admin',  'admin123',  NULL),
    ('user1',  'user123',   NULL),
    ('user2',  'user123',   NULL),
    ('alice',  'alice123',  NULL),
    ('bob',    'bob123',    NULL)
ON DUPLICATE KEY UPDATE username = username;

-- 示例项目（与 implementation plan 中 Engineering / Design 对应）
INSERT INTO projects (name, identifier) VALUES
    ('Engineering', 'ENG'),
    ('Design',      'DES')
ON DUPLICATE KEY UPDATE name = VALUES(name);
