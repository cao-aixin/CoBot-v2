-- ============================================================
-- 增量升级脚本：登录账号（学号 / 工号）体系 + 显示昵称拆分
--
-- 背景
-- ----
-- 旧版本用「姓名」既当登录账号又当聊天昵称，带来两个问题：
--   1) 姓名易重复（sys_user 只有主键索引，username 无唯一约束），只能靠应用层
--      selectCount 预检，存在并发竞态；
--   2) 登录与聊天昵称耦合，改昵称即改登录名，语义混乱。
--
-- 本脚本做的事
-- ------------
--   1) sys_user 新增 account 列，作为「唯一登录凭据（学号 / 工号）」；
--   2) 存量数据平滑迁移：account = 现有 username，老账号（张三 / 李四…）仍可用原值登录；
--   3) account 加唯一索引 uk_account，从数据库层彻底杜绝重复注册（兜住并发竞态）；
--   4) username 语义收敛为「显示昵称 / 姓名（可重名，仅用于展示）」。
--
-- 可重复执行：列 / 索引存在性先判断，已存在则自动跳过。
-- 执行：mysql -uroot -p < upgrade-account.sql
-- ============================================================

USE cobot_db;

-- ---------- 工具过程：列存在性判断（不存在才 ALTER） ----------
DROP PROCEDURE IF EXISTS cobot_add_column;
DELIMITER $$
CREATE PROCEDURE cobot_add_column(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(500))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @s = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
        PREPARE stmt FROM @s;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- ---------- 工具过程：唯一索引存在性判断（不存在才 ALTER） ----------
DROP PROCEDURE IF EXISTS cobot_add_unique_index;
DELIMITER $$
CREATE PROCEDURE cobot_add_unique_index(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN cols VARCHAR(200))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx) THEN
        SET @s = CONCAT('ALTER TABLE `', tbl, '` ADD UNIQUE KEY `', idx, '` (', cols, ')');
        PREPARE stmt FROM @s;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 1) 新增 account 列（先允许 NULL，便于回填存量数据）
CALL cobot_add_column('sys_user', 'account', "account VARCHAR(50) NULL COMMENT '登录账号（学号/工号）' AFTER id");

-- 2) 存量迁移：account 回填为现有 username（保证老账号仍可用原值登录）
UPDATE sys_user SET account = username WHERE account IS NULL OR account = '';

-- 3) account 收紧为 NOT NULL（唯一登录凭据）
ALTER TABLE sys_user MODIFY COLUMN account VARCHAR(50) NOT NULL COMMENT '登录账号（学号/工号），唯一登录凭据';

-- 4) 唯一索引：数据库层兜住并发重复注册
CALL cobot_add_unique_index('sys_user', 'uk_account', '`account`');

-- 5) username 语义收敛为「显示昵称 / 姓名（仅改注释；保持 NOT NULL）」
ALTER TABLE sys_user MODIFY COLUMN username VARCHAR(50) NOT NULL COMMENT '显示昵称/姓名（可重名，仅用于展示）';

DROP PROCEDURE IF EXISTS cobot_add_column;
DROP PROCEDURE IF EXISTS cobot_add_unique_index;

-- ---------- 校验输出 ----------
SELECT '账号迁移结果' AS result;
SELECT id, account, username FROM sys_user ORDER BY id;

SELECT 'uk_account 索引信息' AS result;
SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_account';
