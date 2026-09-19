package com.cobot.service;

import com.cobot.entity.SysTask;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 导出单元测试
 *
 * <p>关键不是"有没有字节"，而是<b>导出的文件能否被 Excel 正确读回</b> ——
 * 所以这里用 POI 反向解析一遍，校验表头与数据行。
 */
class ExportServiceTest {

    private final ExportService exportService = new ExportService();

    @Test
    @DisplayName("导出的 xlsx 可被重新解析，表头与数据行正确")
    void exportTasksRoundTrip() throws Exception {
        byte[] bytes = exportService.exportTasks("研发一组", List.of(task(1L, "完成登录接口开发", "张三"),
                task(2L, "整理接口文档", "李四")));
        assertNotNull(bytes, "导出不应返回 null");
        assertTrue(bytes.length > 0, "导出内容不应为空");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("任务ID", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("任务内容", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("负责人", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals(3, sheet.getLastRowNum(), "标题 + 表头 + 2 行数据 = 末行索引 3");
            assertEquals("完成登录接口开发", sheet.getRow(2).getCell(1).getStringCellValue());
        }
    }

    @Test
    @DisplayName("空任务列表也能导出（只有标题与表头）")
    void exportEmptyList() throws Exception {
        byte[] bytes = exportService.exportTasks("空团队", List.of());
        assertNotNull(bytes);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(1, wb.getSheetAt(0).getLastRowNum(), "空列表时末行应为表头行");
        }
    }

    private SysTask task(Long id, String content, String owner) {
        SysTask task = new SysTask();
        task.setId(id);
        task.setTeamId(1L);
        task.setTaskContent(content);
        task.setOwnerName(owner);
        task.setDeadline(LocalDate.now().plusDays(2));
        task.setPriority("高");
        task.setTaskStatus("进行中");
        task.setCreateTime(java.time.LocalDateTime.now());
        return task;
    }
}
