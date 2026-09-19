-- ============================================================
-- CoBot-v2 数据库初始化脚本（Web 网页聊天室 + 双模式 Agent 版）
-- 执行方式：mysql -uroot -p < cobot_db.sql
-- 或在 Navicat / IDEA Database 工具中直接运行
-- ============================================================

CREATE DATABASE IF NOT EXISTS cobot_db DEFAULT CHARACTER SET utf8mb4;
USE cobot_db;

-- 用户表（学号/工号账号 + 密码登录；username 为显示昵称，仅用于展示）
--   account  = 唯一登录凭据（学号/工号），注册后不可改
--   username = 显示昵称/姓名（可重名，聊天与任务中展示用）
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    account VARCHAR(50) NOT NULL COMMENT '登录账号（学号/工号），唯一登录凭据',
    username VARCHAR(50) NOT NULL COMMENT '显示昵称/姓名（可重名，仅用于展示）',
    password VARCHAR(100) NOT NULL COMMENT '密码（演示环境明文，生产须 bcrypt 等加密）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 团队表（一个团队 = 一个聊天室）
CREATE TABLE IF NOT EXISTS sys_team (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '团队ID',
    team_name VARCHAR(100) NOT NULL COMMENT '团队名称',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队表';

-- 团队成员表（用户-团队 多对多）
CREATE TABLE IF NOT EXISTS sys_team_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    team_id BIGINT NOT NULL COMMENT '团队ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    UNIQUE KEY uk_team_user (team_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队成员表';

-- 聊天消息表（用户消息 + 智能体消息统一存储）
CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    team_id BIGINT NOT NULL COMMENT '所属团队',
    sender_id BIGINT NULL COMMENT '发送人ID，bot消息为null',
    sender_type TINYINT NOT NULL COMMENT '0用户 1智能体',
    msg_content TEXT NOT NULL COMMENT '消息内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    KEY idx_team_time (team_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';

-- 任务表
CREATE TABLE IF NOT EXISTS sys_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '任务ID',
    team_id BIGINT NOT NULL COMMENT '所属团队',
    owner_name VARCHAR(50) NOT NULL COMMENT '负责人',
    task_content TEXT NOT NULL COMMENT '任务内容',
    deadline DATE NULL COMMENT '截止日期',
    priority VARCHAR(10) DEFAULT '普通' COMMENT '优先级：高/普通/低',
    task_status VARCHAR(20) DEFAULT '待确认' COMMENT '状态：待确认、进行中、已完成、归档',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_team_status (team_id, task_status),
    KEY idx_deadline (deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务表';

-- 提醒日志表（任务到期提醒 + 独立定时提醒）
CREATE TABLE IF NOT EXISTS sys_remind_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '关联任务ID，独立提醒用0占位',
    team_id BIGINT DEFAULT 1 COMMENT '推送目标团队（聊天室）ID',
    remind_content VARCHAR(500) NULL COMMENT '提醒内容（独立提醒保存原话）',
    remind_time DATETIME NOT NULL COMMENT '提醒触发时间',
    is_push TINYINT DEFAULT 0 COMMENT '0未推送 1已推送',
    KEY idx_push (is_push, remind_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提醒日志表';

-- AI 生成产物表（团队洞察 / 项目计划 / 一键 PPT）
--   生成内容与 .pptx 文件字节一并落库，保证重启后仍可从"生成历史"重复下载
CREATE TABLE IF NOT EXISTS ai_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '产物ID',
    team_id BIGINT NOT NULL COMMENT '所属团队',
    user_id BIGINT NOT NULL COMMENT '发起人生成用户ID',
    gen_type VARCHAR(20) NOT NULL COMMENT '生成类型：insight=团队洞察 / plan=项目计划 / ppt=演示文稿',
    topic VARCHAR(200) NULL COMMENT '生成主题',
    theme VARCHAR(20) NULL COMMENT 'PPT 配色主题：blue/green/purple/orange/teal/red/dark',
    file_name VARCHAR(200) NULL COMMENT '可下载文件名（PPT 类产物才有）',
    content MEDIUMTEXT NULL COMMENT '生成的正文内容（Markdown 文本）',
    file_data LONGBLOB NULL COMMENT '生成的文件二进制（.pptx）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    KEY idx_team_type_time (team_id, gen_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 生成产物表';

-- ============================================================
-- 种子数据（本地演示用，可按需清空重建）
-- ============================================================

-- 演示账号：account 即登录用的学号/工号；username 为聊天中显示的姓名
-- 演示阶段 account = username（张三 / 李四 / 王五 / 赵六），便于用「张三 / 123456」登录
INSERT INTO sys_user (id, account, username, password) VALUES
(1, '张三', '张三', '34768e01b823efa01239e1ffc47ea0370ff9d4d764305c80a889ba54b09e0490'),
(2, '李四', '李四', '34768e01b823efa01239e1ffc47ea0370ff9d4d764305c80a889ba54b09e0490'),
(3, '王五', '王五', '34768e01b823efa01239e1ffc47ea0370ff9d4d764305c80a889ba54b09e0490'),
(4, '赵六', '赵六', '34768e01b823efa01239e1ffc47ea0370ff9d4d764305c80a889ba54b09e0490');
-- 上面哈希 = SHA2(CONCAT('CoBot@2026#salt','123456'),256)，即演示密码 123456

INSERT INTO sys_team (id, team_name, invite_code) VALUES
(1, '研发一组', 'R8K2QA'),
(2, '产品二组', 'P4M9XW');

-- team1：张三为负责人（owner），李四/王五为普通成员
-- team2：李四为负责人（owner），赵六为普通成员
INSERT INTO sys_team_member (team_id, user_id, role) VALUES
(1, 1, 'owner'), (1, 2, 'member'), (1, 3, 'member'),
(2, 2, 'owner'), (2, 4, 'member');

-- 预置两条任务（方便看板演示；deadline 会随演示需要自行调整）
INSERT INTO sys_task (team_id, owner_name, task_content, deadline, priority, task_status) VALUES
(1, '张三', '完成登录接口开发', DATE_ADD(CURDATE(), INTERVAL 2 DAY), '高', '进行中'),
(1, '李四', '整理接口文档', CURDATE(), '普通', '进行中');

-- 预置欢迎消息
INSERT INTO chat_message (team_id, sender_id, sender_type, msg_content) VALUES
(1, NULL, 1, '大家好，我是 CoBot 智能体 🤖 你可以：@CoBot 张三 周五 完成登录页 —— 创建任务；输入"我的任务" —— 查询任务；输入"提醒我 明天9点 开会" —— 定时提醒。'),
(2, NULL, 1, '欢迎来到产品二组聊天室，@CoBot 即可唤醒我。');
