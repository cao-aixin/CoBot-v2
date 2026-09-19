package com.cobot.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯文本清洗工具单元测试（不依赖 Spring 上下文）
 *
 * <p>覆盖：各条清洗规则、幂等性、空值安全、清洗后为空的兜底、中文内容不被破坏，
 * 以及正文中的 # / - / . 不被误删。
 */
class PlainTextCleanerTest {

    // ==================== 结构化语法 ====================

    @Test
    @DisplayName("标题符：行首 # 去掉，保留标题文字")
    void headingMarkerRemoved() {
        assertEquals("「研发一组」团队数据分析", PlainTextCleaner.clean("# 「研发一组」团队数据分析"));
        assertEquals("一、核心指标", PlainTextCleaner.clean("## 一、核心指标"));
        assertEquals("三、下周建议", PlainTextCleaner.clean("### 三、下周建议"));
    }

    @Test
    @DisplayName("强调符：** / __ / * / _ 去掉")
    void emphasisRemoved() {
        assertEquals("整体风险等级：高", PlainTextCleaner.clean("**整体风险等级：高**"));
        assertEquals("加粗文字", PlainTextCleaner.clean("__加粗文字__"));
        assertEquals("斜体", PlainTextCleaner.clean("*斜体*"));
        assertEquals("下划线强调", PlainTextCleaner.clean("_下划线强调_"));
    }

    @Test
    @DisplayName("删除线：~~ 去掉")
    void strikethroughRemoved() {
        assertEquals("废弃内容", PlainTextCleaner.clean("~~废弃内容~~"));
    }

    @Test
    @DisplayName("代码：围栏整行删除，行内反引号去掉")
    void codeRemoved() {
        String input = "```java\nint a = 1;\n```\n正文 `code` 结束";
        assertEquals("int a = 1;\n正文 code 结束", PlainTextCleaner.clean(input));
    }

    @Test
    @DisplayName("引用符：行首 > 去掉")
    void blockquoteRemoved() {
        assertEquals("引用内容", PlainTextCleaner.clean("> 引用内容"));
        assertEquals("嵌套引用", PlainTextCleaner.clean(">> 嵌套引用"));
    }

    @Test
    @DisplayName("链接与图片：保留文字，丢掉 url")
    void linksAndImages() {
        assertEquals("见官网和配图", PlainTextCleaner.clean("见[官网](https://x.com)和![配图](http://y.png)"));
    }

    @Test
    @DisplayName("表格：分隔行删除，其余行去 | 并用顿号连接")
    void tableCleaned() {
        String input = "| 指标 | 数值 |\n| --- | --- |\n| 任务总数 | 45 条 |";
        assertEquals("指标、数值\n任务总数、45 条", PlainTextCleaner.clean(input));
        assertFalse(PlainTextCleaner.clean(input).contains("|"));
    }

    @Test
    @DisplayName("列表前缀：无序列表符号去掉，有序列表改为中文序号")
    void listPrefixCleaned() {
        assertEquals("任务一\n任务二\n任务三", PlainTextCleaner.clean("- 任务一\n* 任务二\n+ 任务三"));
        // 有序列表：1. 改为中文习惯的 1、（保留序号）
        assertEquals("1、第一条\n2、第二条", PlainTextCleaner.clean("1. 第一条\n2. 第二条"));
    }

    @Test
    @DisplayName("水平分割线整行删除")
    void horizontalRuleRemoved() {
        assertEquals("上文\n下文", PlainTextCleaner.clean("上文\n---\n下文"));
        assertEquals("上文\n下文", PlainTextCleaner.clean("上文\n***\n下文"));
    }

    // ==================== 装饰符号 / emoji ====================

    @Test
    @DisplayName("emoji 与装饰符号清除，文字保留")
    void emojiRemoved() {
        String out = PlainTextCleaner.clean("✅ 任务完成 ❌ 失败 📊 数据 ▍标题 ● 要点");
        assertFalse(out.contains("✅"));
        assertFalse(out.contains("❌"));
        assertFalse(out.contains("📊"));
        assertFalse(out.contains("▍"));
        assertFalse(out.contains("●"));
        assertTrue(out.contains("任务完成"));
        assertTrue(out.contains("失败"));
        assertTrue(out.contains("数据"));
        assertTrue(out.contains("标题"));
        assertTrue(out.contains("要点"));
    }

    @Test
    @DisplayName("零宽字符与变体选择符清除")
    void invisibleCharsRemoved() {
        assertEquals("数据测试结束", PlainTextCleaner.clean("数\u200B据\u200C测试\uFEFF结束"));
        assertEquals("注意警告", PlainTextCleaner.clean("\u26A0\uFE0F注意警告"));
    }

    // ==================== 空白与结构 ====================

    @Test
    @DisplayName("全角空格归一为普通空格")
    void fullWidthSpaceNormalized() {
        assertEquals("标题 内容", PlainTextCleaner.clean("标题\u3000内容"));
    }

    @Test
    @DisplayName("连续空行折叠为一个空行")
    void blankLinesCollapsed() {
        assertEquals("A\n\nB", PlainTextCleaner.clean("A\n\n\n\nB"));
    }

    // ==================== 契约：空值 / 兜底 / 幂等 ====================

    @Test
    @DisplayName("空值安全：null / 空串 / 纯空白返回空字符串")
    void nullAndBlankSafe() {
        assertEquals("", PlainTextCleaner.clean(null));
        assertEquals("", PlainTextCleaner.clean(""));
        assertEquals("", PlainTextCleaner.clean("   \n\t "));
    }

    @Test
    @DisplayName("清洗后为空时回落到 trim 后的原文，不抹空内容")
    void fallbackWhenNothingLeft() {
        assertEquals("✅", PlainTextCleaner.clean("✅"));
        assertEquals("📊📌", PlainTextCleaner.clean("📊📌"));
    }

    @Test
    @DisplayName("幂等：clean(clean(x)) == clean(x)")
    void idempotent() {
        String x = "# 标题\n\n**风险等级：高**\n\n| 指标 | 数值 |\n| --- | --- |\n| 任务 | 5 |\n\n"
                + "- 要点A\n1. 事项B\n\n> 引用\n\n✅ 完成 📊";
        String once = PlainTextCleaner.clean(x);
        assertEquals(once, PlainTextCleaner.clean(once));
    }

    // ==================== 不误删（保护既有内容） ====================

    @Test
    @DisplayName("中文标点与汉字内容不被破坏")
    void chineseContentIntact() {
        String cn = "项目目标：完成需求分析，输出设计文档。（第一版）「重点」「A」《方案》——完毕……";
        assertEquals(cn, PlainTextCleaner.clean(cn));
    }

    @Test
    @DisplayName("ASCII 标识符中的下划线不被误删（snake_case / 数字）")
    void underscoreInIdentifierKept() {
        assertEquals("变量 max_task_count 与 100_000", PlainTextCleaner.clean("变量 max_task_count 与 100_000"));
    }

    @Test
    @DisplayName("正文里的 # 不被误删（仅行首标题符去除）")
    void hashInsideBodyKept() {
        assertEquals("办卡编号 #123 已处理", PlainTextCleaner.clean("办卡编号 #123 已处理"));
        assertEquals("标题", PlainTextCleaner.clean("# 标题"));
    }

    @Test
    @DisplayName("正文里的 - 与 . 不被误删（连字符、版本号、小数）")
    void dashAndDotKept() {
        assertEquals("第 3-5 周完成 v1.2 版本", PlainTextCleaner.clean("第 3-5 周完成 v1.2 版本"));
    }
}
