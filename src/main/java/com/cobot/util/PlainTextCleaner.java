package com.cobot.util;

import java.util.regex.Pattern;

/**
 * 纯文本清洗工具（AI 输出"去特殊符号"的统一收口）
 *
 * <p>设计背景：大模型天然倾向于输出 Markdown。本项目的聊天室与 AI 工作台都用
 * {@code white-space: pre-wrap} 原样打印文本，一旦模型吐出 {@code #}、{@code **}、
 * {@code | --- |} 这类语法符号，界面上就会直接露出"语法垃圾"。本工具负责在
 * <b>落库 / 返回前端之前</b>把大模型可能吐出的 Markdown 语法与装饰性符号清洗为纯文本。
 *
 * <p>处理规则（自上而下顺序执行）：
 * <ol>
 *   <li>代码围栏行（以 <code>```</code> 或 <code>~~~</code> 开头的整行）直接删除；行内反引号去除</li>
 *   <li>水平分割线（如 <code>---</code>、<code>***</code>、<code>___</code>）整行删除</li>
 *   <li>Markdown 表格分隔行（如 <code>| --- | --- |</code>）整行删除</li>
 *   <li>Markdown 标题符：行首 <code>#{1,6}</code> 去掉，保留标题文字</li>
 *   <li>引用符：行首 <code>&gt;</code> 去掉</li>
 *   <li>列表前缀：<code>-</code>、<code>*</code>、<code>+</code> 行首去掉；有序列表 <code>1. </code> 变为中文习惯的 <code>1、</code></li>
 *   <li>表格行：去掉 <code>|</code>，单元格之间用中文顿号「、」连接，保持单行可读</li>
 *   <li>强调 / 删除线：<code>**</code>、<code>__</code>、<code>~~</code>、<code>*</code> 去除；
 *       <code>_</code> 仅当作强调符去除，且刻意保护 ASCII 标识符（如 <code>snake_case</code>、<code>100_000</code>）</li>
 *   <li>链接 / 图片：<code>[文字](url)</code> 保留文字丢掉 url，<code>![alt](url)</code> 保留 alt</li>
 *   <li>emoji 与装饰符号：清掉 emoji / 杂项符号 / 箭头 / 几何图形 / 块元素 / 间隔号等区段，以及零宽字符与变体选择符</li>
 *   <li>空白清理：全角空格与特殊空格归一为普通空格、行尾空白清理、连续 3 个以上换行折叠为 1 个空行、整体 trim</li>
 * </ol>
 *
 * <p><b>保留</b>：汉字、英文字母、数字；中文标点（，。、；：！？（）「」《》【】——……）；
 * 常规西文标点（{@code , . ; : ! ? ( ) [ ] - / % + = @ #}）。
 * 注意 {@code #} 只在<b>行首作为标题符</b>时去掉，正文里的 {@code #} 一律保留。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li><b>空值安全</b>：入参为 {@code null}、空串或纯空白时，一律返回空字符串 {@code ""}（而非 null）。
 *       调用方可据此用 {@code isEmpty()} 判断"无内容"。</li>
 *   <li><b>幂等</b>：保证 {@code clean(clean(x)).equals(clean(x))}，可安全重复清洗。</li>
 *   <li><b>不清空内容</b>：若清洗后结果为空但原文非空（例如原文本身就是一串表情符号），
 *       则回落到 {@code text.trim()}，做防御性兜底，避免把整段内容抹成空串。</li>
 * </ul>
 *
 * <p>线程安全：本类无可变状态，所有正则均为静态常量，可并发调用。
 *
 * @author CoBot 团队
 */
public final class PlainTextCleaner {

    /** 工具类不允许实例化 */
    private PlainTextCleaner() {
    }

    /** 代码围栏行：以 ``` 或 ~~~ 开头的整行 */
    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*(```|~~~).*$");

    /** 水平分割线：由 3 个以上 - / * / _ 组成、可含空格的整行 */
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*(?:[-*_]\\s*){3,}$");

    /** Markdown 表格分隔行：整行只由 | : - 与空白组成（配合"必须含 -"判定） */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^[\\s|:\\-]+$");

    /** 图片：![alt](url) */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");

    /** 链接：[文字](url) */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");

    /** 下划线强调符：去除单独出现的 _，但保护 ASCII 标识符中的下划线 */
    private static final Pattern UNDERSCORE = Pattern.compile("(?<![A-Za-z0-9_])_|_(?![A-Za-z0-9_])");

    /** 需要归一的空白：不间断空格、各类 Unicode 空格、全角空格 */
    private static final Pattern EXOTIC_SPACE = Pattern.compile("[\\u00A0\\u2000-\\u200A\\u3000]+");

    /**
     * 装饰性符号 / emoji / 零宽字符区段
     *
     * <p>覆盖：emoji 与图形符号（1F000–1FAFF）、杂项符号与装饰（2600–27BF）、箭头（2190–21FF）、
     * 技术符号（2300–23FF）、杂项符号与箭头（2B00–2BFF）、制表符（2500–257F）、块元素（2580–259F）、
     * 几何图形（25A0–25FF）、零宽字符（200B–200F）、变体选择符（FE00–FE0F）、BOM（FEFF），
     * 以及常见的间隔号 / 项目符号（· • ‧ ∙ ・ ･）。
     */
    private static final Pattern DECORATION = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}"
                    + "\\x{2600}-\\x{27BF}"
                    + "\\x{2190}-\\x{21FF}"
                    + "\\x{2300}-\\x{23FF}"
                    + "\\x{2B00}-\\x{2BFF}"
                    + "\\x{2500}-\\x{257F}"
                    + "\\x{2580}-\\x{259F}"
                    + "\\x{25A0}-\\x{25FF}"
                    + "\\x{200B}-\\x{200F}"
                    + "\\x{FE00}-\\x{FE0F}"
                    + "\\x{FEFF}"
                    + "\\u00B7\\u2022\\u2027\\u2219\\u30FB\\uFF65"
                    + "]");

    /**
     * 把一段可能含 Markdown / 装饰符号的文本清洗为纯文本。
     *
     * @param text 待清洗文本，可为 {@code null}
     * @return 清洗后的纯文本；入参为 null / 空白时返回 {@code ""}；清洗后为空时回落 {@code text.trim()}
     */
    public static String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 统一换行为 \n，便于按行处理
        String src = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = src.split("\n", -1);

        StringBuilder sb = new StringBuilder(src.length());
        for (String line : lines) {
            String trimmed = line.strip();

            // 1. 代码围栏整行丢弃
            if (CODE_FENCE.matcher(line).matches()) {
                continue;
            }
            // 2. 水平分割线整行丢弃
            if (!trimmed.isEmpty() && HORIZONTAL_RULE.matcher(trimmed).matches()) {
                continue;
            }
            // 3. 表格分隔行整行丢弃（必须同时含 | 与 -）
            if (!trimmed.isEmpty() && trimmed.indexOf('-') >= 0
                    && TABLE_SEPARATOR.matcher(trimmed).matches()) {
                continue;
            }

            // 4. 行内结构清理（顺序：引用 -> 标题 -> 列表 -> 表格）
            String out = line;
            out = out.replaceFirst("^\\s*>+\\s*", "");                 // 引用符
            out = out.replaceFirst("^\\s*#{1,6}\\s*", "");              // 标题符
            out = out.replaceFirst("^\\s*[-*+]\\s+", "");               // 无序列表符
            out = out.replaceFirst("^(\\s*)(\\d{1,3})\\.\\s+", "$1$2、"); // 有序列表 -> 中文序号
            if (out.indexOf('|') >= 0) {                                // 表格行 -> 顿号连接
                out = joinTableRow(out);
            }
            sb.append(out.strip()).append('\n');
        }
        String body = sb.toString();

        // 5. 行内语法：图片 / 链接 / 行内代码 / 强调 / 删除线
        body = IMAGE.matcher(body).replaceAll("$1");   // ![alt](url) -> alt
        body = LINK.matcher(body).replaceAll("$1");    // [文字](url) -> 文字
        body = body.replace("`", "");                  // 行内代码
        body = body.replace("**", "").replace("__", "").replace("~~", "");
        body = body.replace("*", "");                  // 残余强调星号
        body = UNDERSCORE.matcher(body).replaceAll(""); // 下划线强调（保护 snake_case）

        // 6. 装饰符号 / emoji / 零宽字符
        body = DECORATION.matcher(body).replaceAll("");

        // 7. 空白归一：特殊空格 -> 普通空格（全角空格、各类 Unicode 空格）
        body = EXOTIC_SPACE.matcher(body).replaceAll(" ");

        // 8. 逐行去首尾空白（emoji/符号清除后可能残留孤立空格）+ 折叠连续空行 + 整体 trim
        //    放在符号清除之后再做一次逐行 trim，保证 clean 幂等
        StringBuilder rebuilt = new StringBuilder(body.length());
        for (String one : body.split("\n", -1)) {
            rebuilt.append(one.strip()).append('\n');
        }
        body = rebuilt.toString().replaceAll("\\n{3,}", "\n\n").strip();

        // 9. 防御：清洗后为空则回落原文，避免把内容抹空
        return body.isEmpty() ? text.strip() : body;
    }

    /**
     * 把一行 Markdown 表格行的 <code>|</code> 去掉，单元格之间用中文顿号连接。
     *
     * <p>示例：<code>| 任务总数 | 45 条 |</code> -&gt; <code>任务总数、45 条</code>
     */
    private static String joinTableRow(String line) {
        String[] cells = line.split("\\|", -1);
        StringBuilder sb = new StringBuilder(line.length());
        for (String cell : cells) {
            String c = cell.strip();
            if (c.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
