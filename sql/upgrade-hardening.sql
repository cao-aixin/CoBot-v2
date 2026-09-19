-- ============================================================
-- CoBot-v2 加固升级脚本（可重复执行，已存在则自动跳过）
--   包含：
--     1) 登录会话落库（重启不掉线）
--     2) 待确认任务落库（重启不丢审批）
--     3) 操作审计日志
--     4) 团队邀请码有效期 / 使用次数 / 公告
--     5) 消息撤回、附件、@提及
--     6) 未读消息状态
--     7) AI 产物大字段外置（file_path）
-- 执行：mysql -uroot -p < upgrade-hardening.sql
-- ============================================================

USE cobot_db;

-- ---------- 通用：列存在性判断的工具过程 ----------
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

-- ============================================================
-- 1. 登录会话表：Token 落库，服务重启后仍可继续使用
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_session (
    token       CHAR(32)     NOT NULL COMMENT '登录令牌',
    user_id     BIGINT       NOT NULL COMMENT '用户ID',
    username    VARCHAR(50)  NULL     COMMENT '用户名（冗余，免联表）',
    expire_at   DATETIME     NOT NULL COMMENT '过期时间（滑动续期）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间',
    PRIMARY KEY (token),
    KEY idx_session_user (user_id),
    KEY idx_session_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录会话表';

-- ============================================================
-- 2. 待确认任务表：AI/正则识别出的任务先落库暂存，重启不丢
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_pending_task (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id        BIGINT      NOT NULL COMMENT '团队ID',
    requester_name VARCHAR(50) NOT NULL COMMENT '发起人用户名',
    owner_name     VARCHAR(50) NULL     COMMENT '任务负责人',
    task_content   TEXT        NULL     COMMENT '任务内容',
    deadline       VARCHAR(20) NULL     COMMENT '截止日期 yyyy-MM-dd（原样保留，确认时解析）',
    priority       VARCHAR(10) NULL     COMMENT '优先级',
    create_time    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '暂存时间',
    UNIQUE KEY uk_team_requester (team_id, requester_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待确认任务暂存表';

-- ============================================================
-- 3. 操作审计日志表：记录关键管理动作
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id     BIGINT       NULL COMMENT '所属团队（可为空，如登录）',
    user_id     BIGINT       NULL COMMENT '操作人ID',
    username    VARCHAR(50)  NULL COMMENT '操作人用户名',
    action      VARCHAR(40)  NOT NULL COMMENT '动作标识，如 TASK_DELETE',
    target      VARCHAR(120) NULL COMMENT '操作对象（任务ID/成员名等）',
    detail      VARCHAR(500) NULL COMMENT '补充说明',
    ip          VARCHAR(50)  NULL COMMENT '来源 IP',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    KEY idx_audit_team_time (team_id, create_time),
    KEY idx_audit_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- ============================================================
-- 4. 未读消息状态表：记录每个成员在某聊天室读到哪条消息
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_read_state (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id      BIGINT NOT NULL COMMENT '团队ID',
    user_id      BIGINT NOT NULL COMMENT '用户ID',
    last_read_id BIGINT DEFAULT 0 COMMENT '已读到的最大消息ID',
    UNIQUE KEY uk_read_team_user (team_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息已读状态表';

-- ============================================================
-- 5. 团队表扩展：邀请码有效期 / 使用次数 / 公告
-- ============================================================
CALL cobot_add_column('sys_team', 'invite_expire_at',  "invite_expire_at DATETIME NULL COMMENT '邀请码过期时间，NULL=永久有效'");
CALL cobot_add_column('sys_team', 'invite_max_use',    "invite_max_use INT DEFAULT 0 COMMENT '邀请码最大使用次数，0=不限'");
CALL cobot_add_column('sys_team', 'invite_used_count', "invite_used_count INT DEFAULT 0 COMMENT '邀请码已使用次数'");
CALL cobot_add_column('sys_team', 'notice',            "notice VARCHAR(500) NULL COMMENT '团队公告'");

-- ============================================================
-- 6. 团队成员表：入团时间（越权排查 / 成员列表排序用）
-- ============================================================
CALL cobot_add_column('sys_team_member', 'join_time', "join_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间'");

-- ============================================================
-- 7. 聊天消息表扩展：撤回 / 消息类型 / 附件 / @提及
-- ============================================================
CALL cobot_add_column('chat_message', 'revoked',    "revoked TINYINT DEFAULT 0 COMMENT '是否已撤回：0否 1是'");
CALL cobot_add_column('chat_message', 'msg_type',   "msg_type VARCHAR(20) DEFAULT 'text' COMMENT '消息类型：text/image/file'");
CALL cobot_add_column('chat_message', 'file_name',  "file_name VARCHAR(200) NULL COMMENT '附件原始文件名'");
CALL cobot_add_column('chat_message', 'file_path',  "file_path VARCHAR(300) NULL COMMENT '附件存储相对路径'");
CALL cobot_add_column('chat_message', 'file_size',  "file_size BIGINT NULL COMMENT '附件字节数'");
CALL cobot_add_column('chat_message', 'mentions',   "mentions VARCHAR(500) NULL COMMENT '@提及的用户名，逗号分隔'");

-- ============================================================
-- 8. AI 产物：文件外置，库里只存路径（避免 LONGBLOB 拖慢查询）
-- ============================================================
CALL cobot_add_column('ai_artifact', 'file_path', "file_path VARCHAR(300) NULL COMMENT '文件外置存储相对路径（新产物）'");

DROP PROCEDURE IF EXISTS cobot_add_column;

-- ============================================================
-- 9. 存量数据修正
-- ============================================================
-- 邀请码初始化为永久有效（NULL 语义即永久，这里仅确保列值可读）
UPDATE sys_team SET invite_used_count = 0 WHERE invite_used_count IS NULL;
UPDATE sys_team SET invite_max_use = 0 WHERE invite_max_use IS NULL;
UPDATE chat_message SET revoked = 0 WHERE revoked IS NULL;
UPDATE chat_message SET msg_type = 'text' WHERE msg_type IS NULL;

-- 老会话表清理（每次执行顺带清掉过期会话）
DELETE FROM sys_session WHERE expire_at < NOW();

SELECT '加固升级脚本执行完成' AS result;
