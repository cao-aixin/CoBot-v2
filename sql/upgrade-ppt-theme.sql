-- ============================================================
-- AI 工作台 · 配色主题升级脚本
-- 用途：为 PPT 产物记录所选配色主题，便于生成历史回显与"换配色重生成"
-- 执行：mysql -uroot -p < upgrade-ppt-theme.sql
-- ============================================================

USE cobot_db;

-- 已有库：补列（字段可为空，历史数据 theme 为空表示早期固定蓝色版本）
ALTER TABLE ai_artifact
    ADD COLUMN theme VARCHAR(20) NULL COMMENT 'PPT 配色主题标识：blue/green/purple/orange/teal/red/dark' AFTER topic;
