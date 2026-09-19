-- ============================================================
-- CoBot-v2 增量升级脚本：AI 工作台（团队洞察 / 项目计划 / 一键 PPT）
-- 执行方式：mysql -uroot -p < upgrade-ai-feature.sql
-- 说明：已存在的库直接执行本脚本即可，无需重建；重复执行安全（IF NOT EXISTS）
-- ============================================================

USE cobot_db;

-- AI 生成产物表
--   记录每一次 AI 生成行为：团队洞察报告 / 项目计划 / PPT，
--   并把生成出来的 .pptx 文件二进制一并落库，保证服务重启后仍可从"生成历史"重复下载。
CREATE TABLE IF NOT EXISTS ai_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '产物ID',
    team_id BIGINT NOT NULL COMMENT '所属团队',
    user_id BIGINT NOT NULL COMMENT '发起人生成用户ID',
    gen_type VARCHAR(20) NOT NULL COMMENT '生成类型：insight=团队洞察 / plan=项目计划 / ppt=演示文稿',
    topic VARCHAR(200) NULL COMMENT '生成主题（如"校园二手交易平台"）',
    file_name VARCHAR(200) NULL COMMENT '可下载文件名（PPT 类产物才有）',
    content MEDIUMTEXT NULL COMMENT '生成的正文内容（Markdown/JSON 文本，用于前端回显与导出）',
    file_data LONGBLOB NULL COMMENT '生成的文件二进制（.pptx），非文件类产物为 NULL',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    KEY idx_team_type_time (team_id, gen_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 生成产物表';
