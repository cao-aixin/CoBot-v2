package com.cobot.service;

import com.cobot.dto.PptOutlineVO;
import com.cobot.dto.PptTheme;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * PPTX 文件渲染服务（AI 工作台 · 一键生成 PPT 的"最后一公里"）
 *
 * <p>职责划分：大模型只产出大纲文字，本服务负责把大纲渲染成<b>真实可用的 .pptx 文件</b>
 * （Apache POI 直接写 OOXML），下载下来用 PowerPoint / WPS 打开即可放映。
 *
 * <p>版面规范：16:9（960×540 pt），全篇"微软雅黑"（同时设置 latin 与东亚字体，
 * 避免中文回退成宋体）。四类版式：封面 / 目录 / 内容 / 结束页。
 *
 * <p>配色不再写死，而是由 {@link PptTheme} 提供：调用方传入主题，本服务据此取色；
 * 同一份大纲换主题即可得到完全不同的视觉风格（商务蓝 / 生机绿 / 星云紫 / 活力橙 / 青碧 / 中国红 / 极夜金）。
 */
@Service
public class PptxBuildService {

    /** 画布尺寸：960×540 pt = 16:9 */
    private static final int PAGE_WIDTH = 960;
    private static final int PAGE_HEIGHT = 540;

    /** 正文深色 */
    private static final Color TEXT_DARK = new Color(0x2D, 0x34, 0x36);

    /** 正文浅灰（页脚 / 页码） */
    private static final Color TEXT_GRAY = new Color(0x7A, 0x86, 0x99);

    /** 全篇字体 */
    private static final String FONT = "微软雅黑";

    /**
     * 把 PPT 大纲渲染为 .pptx 字节数组（使用默认主题）
     */
    public byte[] build(PptOutlineVO outline) {
        return build(outline, PptTheme.byKeyOrDefault());
    }

    /**
     * 把 PPT 大纲渲染为 .pptx 字节数组
     *
     * @param outline 大纲（主题 / 副标题 / 页面列表）
     * @param theme   配色主题；传 null 时回落默认主题
     * @return .pptx 文件字节
     */
    public byte[] build(PptOutlineVO outline, PptTheme theme) {
        PptTheme t = theme == null ? PptTheme.byKeyOrDefault() : theme;
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(PAGE_WIDTH, PAGE_HEIGHT));

            List<PptOutlineVO.PptSlide> slides = outline.getSlides();
            if (slides == null || slides.isEmpty()) {
                slides = List.of();
            }
            int contentIndex = 0;
            for (PptOutlineVO.PptSlide slide : slides) {
                String type = slide.getType() == null ? "content" : slide.getType();
                switch (type) {
                    case "cover" -> renderCover(ppt, outline, slide, t);
                    case "agenda" -> {
                        renderAgenda(ppt, slide, t);
                        contentIndex = 0;
                    }
                    case "end" -> renderEnd(ppt, slide, t);
                    default -> {
                        contentIndex++;
                        renderContent(ppt, slide, contentIndex, t);
                    }
                }
            }
            // 极端情况下（大模型没给封面）兜底补一张封面与结尾页，保证文件是完整的
            if (slides.isEmpty()) {
                renderCover(ppt, outline, null, t);
                renderEnd(ppt, null, t);
            }
            ppt.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PPT 文件生成失败：" + e.getMessage(), e);
        }
    }

    // ==================== 四类版式 ====================

    /**
     * 封面页：整屏主色底 + 居中大标题 + 副标题
     */
    private void renderCover(XMLSlideShow ppt, PptOutlineVO outline, PptOutlineVO.PptSlide slide, PptTheme t) {
        XSLFSlide s = ppt.createSlide();
        s.getBackground().setFillColor(t.primary());

        // 底部装饰条
        rect(s, 0, PAGE_HEIGHT - 10, PAGE_WIDTH, 10, t.accent());

        String title = slide != null && notBlank(slide.getTitle()) ? slide.getTitle() : outline.getTopic();
        addText(s, 90, 170, PAGE_WIDTH - 180, 120, title, 40, true, Color.WHITE,
                TextParagraph.TextAlign.CENTER);

        // 副标题统一取大纲层面的字段（封面页本身不需要再单独存一份副标题）
        String subtitle = notBlank(outline.getSubtitle()) ? outline.getSubtitle() : "CoBot 智能体";
        addText(s, 90, 300, PAGE_WIDTH - 180, 60, subtitle, 18, false,
                t.coverSubtitle(), TextParagraph.TextAlign.CENTER);

        // 封面分隔线
        rect(s, PAGE_WIDTH / 2 - 60, 285, 120, 3, t.accent());

        addText(s, 90, PAGE_HEIGHT - 70, PAGE_WIDTH - 180, 30,
                "由 CoBot 智能体一键生成", 12, false,
                t.coverFooter(), TextParagraph.TextAlign.CENTER);
    }

    /**
     * 目录页：左侧色块 + 右上标题 + 编号条目
     */
    private void renderAgenda(XMLSlideShow ppt, PptOutlineVO.PptSlide slide, PptTheme t) {
        XSLFSlide s = commonContentSlide(ppt, slide == null ? "目 录" : slide.getTitle(), t);
        List<String> bullets = slide == null ? List.of() : slide.getBullets();
        if (bullets.isEmpty()) {
            bullets = List.of("项目背景", "实施计划", "团队分工", "预期成果");
        }
        double y = 150;
        int index = 1;
        for (String bullet : bullets) {
            // 序号圆点
            rect(s, 80, y + 4, 26, 26, t.accent());
            addText(s, 80, y + 4, 26, 26, String.valueOf(index), 13, true,
                    Color.WHITE, TextParagraph.TextAlign.CENTER);
            addText(s, 122, y, PAGE_WIDTH - 200, 34, bullet, 17, false,
                    TEXT_DARK, TextParagraph.TextAlign.LEFT);
            y += 48;
            index++;
        }
    }

    /**
     * 内容页：标题 + 分隔条 + 项目符号列表 + 页码
     */
    private void renderContent(XMLSlideShow ppt, PptOutlineVO.PptSlide slide, int pageNo, PptTheme t) {
        XSLFSlide s = commonContentSlide(ppt, slide.getTitle(), t);
        List<String> bullets = slide.getBullets();
        double y = 150;
        for (String bullet : bullets) {
            addBullet(s, y, bullet, t);
            // 要点较长时占用两行高度，简单按字数估算，避免文字互相压盖
            y += bullet.length() > 34 ? 74 : 52;
            if (y > PAGE_HEIGHT - 70) {
                break;   // 版面装不下就不再堆叠，保证不溢出
            }
        }
        addText(s, PAGE_WIDTH - 110, PAGE_HEIGHT - 42, 70, 24, String.valueOf(pageNo),
                11, false, TEXT_GRAY, TextParagraph.TextAlign.RIGHT);
    }

    /**
     * 结束页：主色底 + 居中结束语
     */
    private void renderEnd(XMLSlideShow ppt, PptOutlineVO.PptSlide slide, PptTheme t) {
        XSLFSlide s = ppt.createSlide();
        s.getBackground().setFillColor(t.primary());
        String title = slide != null && notBlank(slide.getTitle()) ? slide.getTitle() : "谢谢聆听";
        addText(s, 90, 210, PAGE_WIDTH - 180, 90, title, 40, true, Color.WHITE,
                TextParagraph.TextAlign.CENTER);
        String sub = slide != null && !slide.getBullets().isEmpty()
                ? slide.getBullets().get(0)
                : "欢迎在聊天室继续讨论，由 CoBot 智能体整理";
        addText(s, 90, 310, PAGE_WIDTH - 180, 50, sub, 16, false,
                t.coverSubtitle(), TextParagraph.TextAlign.CENTER);
        rect(s, 0, PAGE_HEIGHT - 10, PAGE_WIDTH, 10, t.accent());
    }

    /**
     * 内容页公共骨架：浅底 + 顶部标题 + 标题下分隔条
     */
    private XSLFSlide commonContentSlide(XMLSlideShow ppt, String title, PptTheme t) {
        XSLFSlide s = ppt.createSlide();
        s.getBackground().setFillColor(t.lightBg());
        // 左侧主色装饰竖条
        rect(s, 0, 0, 14, PAGE_HEIGHT, t.primary());
        addText(s, 62, 52, PAGE_WIDTH - 140, 56, title, 27, true, t.titleColor(),
                TextParagraph.TextAlign.LEFT);
        // 标题下分隔条
        rect(s, 64, 116, 72, 4, t.accent());
        return s;
    }

    // ==================== 绘制辅助 ====================

    /**
     * 画一个纯色矩形（用于色块 / 分隔条 / 序号底）
     *
     * <p>实现方式：用一个"填充了底色、去掉了文本框内边距与描边"的文本框来充当矩形。
     * 相比 createAutoShape()，这条路子完全走 XSLFSimpleShape 的公共 API，
     * 不依赖图形预设（prstGeom）的版本差异，兼容性更稳。
     */
    private XSLFTextBox rect(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        box.clearText();
        box.setFillColor(color);
        box.setLineColor(color);   // 描边与填充同色 = 视觉上无边框
        box.setLineWidth(0);
        return box;
    }

    /**
     * 添加一段文本（可带自动居中）
     */
    private void addText(XSLFSlide slide, double x, double y, double w, double h,
                         String text, double fontSize, boolean bold, Color color,
                         TextParagraph.TextAlign align) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        box.clearText();
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(align);
        p.setLineSpacing(130.0);
        XSLFTextRun run = p.addNewTextRun();
        run.setText(text == null ? "" : text);
        styleRun(run, fontSize, bold, color);
    }

    /**
     * 添加一条项目符号（圆点 + 文本，自动换行）
     */
    private void addBullet(XSLFSlide slide, double y, String text, PptTheme t) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(80, y, PAGE_WIDTH - 150, 60));
        box.clearText();
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.LEFT);
        p.setLineSpacing(135.0);
        p.setIndent(-18.0);          // 悬挂缩进：换行后与首行文字左对齐
        p.setLeftMargin(20.0);
        XSLFTextRun dot = p.addNewTextRun();
        dot.setText("●  ");
        styleRun(dot, 11, false, t.bulletColor());
        XSLFTextRun run = p.addNewTextRun();
        run.setText(text == null ? "" : text);
        styleRun(run, 16, false, TEXT_DARK);
    }

    /**
     * 统一设置字号 / 粗体 / 颜色 / 字体
     *
     * <p>中文必须<b>同时</b>设置 latin 与 East Asian 两组字体，
     * 否则 PowerPoint 打开时中文会回退成宋体，观感差别很大。
     */
    private void styleRun(XSLFTextRun run, double fontSize, boolean bold, Color color) {
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontColor(color);
        run.setFontFamily(FONT, FontGroup.LATIN);
        run.setFontFamily(FONT, FontGroup.EAST_ASIAN);
    }

    /**
     * 安全判空
     */
    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
