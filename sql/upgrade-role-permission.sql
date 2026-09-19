-- ============================================================
-- 增量升级脚本：角色权限体系（负责人 / 普通成员）
-- 适用：已有 cobot_db 数据的旧环境；全新环境直接跑 cobot_db.sql 即可
-- 执行：mysql -uroot -p cobot_db < upgrade-role-permission.sql
-- ============================================================
USE cobot_db;

-- 团队成员表：角色字段（owner=负责人 / member=普通成员）
ALTER TABLE sys_team_member ADD COLUMN role VARCHAR(20) DEFAULT 'member' COMMENT 'owner=队长 member=普通成员';

-- 团队表：邀请码（成员分享、他人凭码加入）
ALTER TABLE sys_team ADD COLUMN invite_code VARCHAR(16) COMMENT '邀请码（6位）';
UPDATE sys_team SET invite_code = 'R8K2QA' WHERE id = 1 AND invite_code IS NULL;
UPDATE sys_team SET invite_code = 'P4M9XW' WHERE id = 2 AND invite_code IS NULL;

-- 指定种子团队负责人
UPDATE sys_team_member SET role = 'owner' WHERE team_id = 1 AND user_id = 1;   -- 张三：研发一组负责人
UPDATE sys_team_member SET role = 'owner' WHERE team_id = 2 AND user_id = 2;   -- 李四：产品二组负责人

-- 校验
SELECT tm.team_id, t.team_name, u.username, tm.role
FROM sys_team_member tm
JOIN sys_user u ON u.id = tm.user_id
JOIN sys_team t ON t.id = tm.team_id
ORDER BY tm.team_id, tm.role DESC;
