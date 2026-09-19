package com.cobot.service;

import com.cobot.entity.SysTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 导出服务（Excel）
 *
 * <p>把任务看板导出成 .xlsx，方便负责人做周会材料或存档。
 * 用 Apache POI 直接生成真实 Excel（不是 CSV 改后缀），打开即是带样式的表格。
 */
@Slf4j
@Service
public class ExportService {

    /** 导出的列定义 */
    private static final String[] HEADERS = {
            "任务ID", "任务内容", "负责人", "截止日期", "优先级", "状态", "创建时间"
    };

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 导出任务清单
     *
     * @param teamName 团队名称（写进标题行）
     * @param tasks    任务列表
     * @return xlsx 字节；失败返回 null
     */
    public byte[] exportTasks(String teamName, List<SysTask> tasks) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("任务看板");

            CellStyle titleStyle = titleStyle(wb);
            CellStyle headStyle = headStyle(wb);
            CellStyle bodyStyle = bodyStyle(wb);

            // 第 1 行：标题（合并单元格）
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(26);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue((teamName == null ? "团队" : teamName) + " 任务看板（共 "
                    + (tasks == null ? 0 : tasks.size()) + " 条）");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
                    0, 0, 0, HEADERS.length - 1));

            // 第 2 行：表头
            Row headRow = sheet.createRow(1);
            headRow.setHeightInPoints(20);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headStyle);
            }

            // 数据行
            int rowIndex = 2;
            if (tasks != null) {
                for (SysTask task : tasks) {
                    Row row = sheet.createRow(rowIndex++);
                    row.setHeightInPoints(18);
                    writeCell(row, 0, String.valueOf(task.getId()), bodyStyle);
                    writeCell(row, 1, task.getTaskContent(), bodyStyle);
                    writeCell(row, 2, task.getOwnerName(), bodyStyle);
                    writeCell(row, 3, task.getDeadline() == null ? "" : task.getDeadline().toString(), bodyStyle);
                    writeCell(row, 4, task.getPriority(), bodyStyle);
                    writeCell(row, 5, task.getTaskStatus(), bodyStyle);
                    writeCell(row, 6, task.getCreateTime() == null ? "" : task.getCreateTime().format(DATE_TIME), bodyStyle);
                }
            }

            // 列宽：任务内容列给宽一些，其余按内容长度粗略估算
            sheet.setColumnWidth(0, 2400);
            sheet.setColumnWidth(1, 14000);
            sheet.setColumnWidth(2, 3200);
            sheet.setColumnWidth(3, 3600);
            sheet.setColumnWidth(4, 2600);
            sheet.setColumnWidth(5, 3000);
            sheet.setColumnWidth(6, 5000);
            // 冻结表头，滚动时列名始终可见
            sheet.createFreezePane(0, 2);

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[CoBot] 导出任务 Excel 失败", e);
            return null;
        }
    }

    private void writeCell(Row row, int index, String value, CellStyle style) {
        Cell cell = row.createCell(index);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    /** 标题样式：加粗、居中、大字号 */
    private CellStyle titleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setFontName("微软雅黑");
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    /** 表头样式：灰底加粗 */
    private CellStyle headStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontName("微软雅黑");
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    /** 正文样式：细边框、垂直居中（内容列允许换行） */
    private CellStyle bodyStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontName("微软雅黑");
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
}
