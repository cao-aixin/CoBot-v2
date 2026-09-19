package com.cobot.dto;

import java.awt.Color;
import java.util.List;

/**
 * PPT 配色主题（AI 工作台 · 一键生成 PPT 的视觉皮肤）
 *
 * <p>之前配色是写死的深蓝，所有演示文稿长得一模一样。这里把配色抽成"主题"：
 * 每个主题只需定义 3 个基准色（主色 / 强调色 / 内容页底色），
 * 封面副标题、页脚等中间色由 {@link #coverSubtitle()} 等方法按比例与白色混合自动推导，
 * 新增主题只要往 {@link #ALL} 里加一行即可。
 *
 * <p>主色用于封面整屏底、内容页左侧装饰竖条与标题文字；
 * 强调色用于分隔条、目录序号块、项目符号圆点；底色用于内容页背景。
 *
 * @param key     主题标识（前端传参用，如 blue / green）
 * @param name    主题中文名（前端下拉展示）
 * @param primary 主色
 * @param accent  强调色
 * @param lightBg 内容页浅底
 */
public record PptTheme(String key, String name, Color primary, Color accent, Color lightBg) {

    /** 默认主题：商务蓝（不指定配色时使用） */
    public static final String DEFAULT_KEY = "blue";

    /**
     * 全部可选主题（顺序即前端下拉的展示顺序）
     *
     * <p>选取原则：主色都压到较深的明度，保证封面白字可读；强调色明度较高，用于小面积点缀。
     */
    public static final List<PptTheme> ALL = List.of(
            new PptTheme("blue", "商务蓝", new Color(0x2B, 0x4C, 0xA0), new Color(0x4B, 0x7B, 0xEC),
                    new Color(0xF7, 0xF9, 0xFD)),
            new PptTheme("green", "生机绿", new Color(0x1B, 0x6E, 0x46), new Color(0x27, 0xAE, 0x60),
                    new Color(0xF5, 0xFB, 0xF7)),
            new PptTheme("purple", "星云紫", new Color(0x53, 0x2B, 0x8C), new Color(0x9B, 0x59, 0xB6),
                    new Color(0xFA, 0xF8, 0xFD)),
            new PptTheme("orange", "活力橙", new Color(0xB8, 0x4A, 0x0A), new Color(0xF3, 0x9C, 0x12),
                    new Color(0xFF, 0xFA, 0xF4)),
            new PptTheme("teal", "青碧", new Color(0x0B, 0x63, 0x70), new Color(0x22, 0xB8, 0xCF),
                    new Color(0xF4, 0xFB, 0xFC)),
            new PptTheme("red", "中国红", new Color(0xA3, 0x1D, 0x1D), new Color(0xE7, 0x4C, 0x3C),
                    new Color(0xFD, 0xF7, 0xF6)),
            new PptTheme("dark", "极夜金", new Color(0x1B, 0x1F, 0x2A), new Color(0xE1, 0xB1, 0x2C),
                    new Color(0xF9, 0xF9, 0xF5))
    );

    /**
     * 按 key 取主题；key 为空或不认识时回落到默认主题（不抛异常，保证生成流程不中断）
     */
    public static PptTheme byKey(String key) {
        if (key != null && !key.isBlank()) {
            for (PptTheme t : ALL) {
                if (t.key.equalsIgnoreCase(key.trim())) {
                    return t;
                }
            }
        }
        return byKeyOrDefault();
    }

    /** 默认主题实例 */
    public static PptTheme byKeyOrDefault() {
        for (PptTheme t : ALL) {
            if (t.key.equals(DEFAULT_KEY)) {
                return t;
            }
        }
        return ALL.get(0);
    }

    /** 封面副标题色：主色向白色大幅提亮，保持低对比的柔和感 */
    public Color coverSubtitle() {
        return mix(primary, Color.WHITE, 0.72);
    }

    /** 封面页脚 / 尾页说明文字色：中等提亮 */
    public Color coverFooter() {
        return mix(primary, Color.WHITE, 0.52);
    }

    /** 内容页标题文字色：直接用主色，与左侧装饰竖条呼应 */
    public Color titleColor() {
        return primary;
    }

    /** 项目符号圆点色 */
    public Color bulletColor() {
        return accent;
    }

    /** 十六进制字符串（前端取色用，形如 #2B4CA0） */
    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 转成给前端用的瘦身视图（只带 hex，不带 java.awt.Color 的杂字段） */
    public ThemeVO toVO() {
        return new ThemeVO(key, name, hex(primary), hex(accent), hex(lightBg));
    }

    /**
     * 两个颜色按比例线性混合（r 为 b 的权重，0=全 a，1=全 b）
     */
    public static Color mix(Color a, Color b, double r) {
        return new Color(
                (int) Math.round(a.getRed() * (1 - r) + b.getRed() * r),
                (int) Math.round(a.getGreen() * (1 - r) + b.getGreen() * r),
                (int) Math.round(a.getBlue() * (1 - r) + b.getBlue() * r));
    }

    /** 前端主题选项视图对象 */
    public record ThemeVO(String key, String name, String primary, String accent, String lightBg) {
    }
}
