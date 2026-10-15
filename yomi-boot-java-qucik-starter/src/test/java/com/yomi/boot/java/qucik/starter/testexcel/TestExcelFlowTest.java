package com.yomi.boot.java.qucik.starter.testexcel;

import com.yomi.boot.java.qucik.starter.testexcel.controller.TestExcelFlowController;
import com.yomi.boot.java.qucik.starter.testexcel.dto.TestExcelImportResult;
import com.yomi.boot.java.qucik.starter.testexcel.service.TestExcelFlowService;
import org.apache.fesod.sheet.FesodSheet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 胶水样板 roundtrip：导出→导入闭环（纯 JVM，不依赖组件开关与外部存储）
 *
 * @author zwei
 */
class TestExcelFlowTest {

    private final TestExcelFlowService flowService = new TestExcelFlowService();

    @Test
    void exportThenImportRoundTrip() {
        var out = new ByteArrayOutputStream();
        flowService.export(out, 3, 2);

        TestExcelImportResult result = flowService.importRows(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(6, result.total());
        assertEquals(6, result.successCount());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void exportRejectsOutOfRangePaging() {
        TestExcelFlowController controller =
                new TestExcelFlowController(flowService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 超大 pageSize 必须在 mockPage 之前拦下（否则 new ArrayList<>(20 亿) 直接 OOM）
        IllegalArgumentException size = assertThrows(IllegalArgumentException.class,
                () -> controller.export(2_000_000_000, 5, response));
        assertTrue(size.getMessage().contains("pageSize"), size.getMessage());

        IllegalArgumentException count = assertThrows(IllegalArgumentException.class,
                () -> controller.export(1000, 101, response));
        assertTrue(count.getMessage().contains("pageCount"), count.getMessage());

        // 上界内正常导出
        assertDoesNotThrow(() -> controller.export(10, 1, response));
        assertTrue(response.getContentAsByteArray().length > 0);
    }

    @Test
    void importCollectsBadRowsWithoutAbort() {
        // 全字符串单元格：年龄"abc"触发转换失败、空姓名/坏邮箱触发校验失败，各记一行错误
        var head = List.of(List.of("姓名"), List.of("年龄"), List.of("邮箱"), List.of("状态"));
        var data = List.of(
                List.<Object>of("", "abc", "not-an-email", "启用"),
                List.<Object>of("张三", "30", "z@y.com", "启用"),
                List.<Object>of("李四", "99", "l@y.com", "禁用"));
        var out = new ByteArrayOutputStream();
        FesodSheet.write(out).head(head).sheet().doWrite(data);

        TestExcelImportResult result = flowService.importRows(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(3, result.total());
        assertEquals(1, result.successCount());
        assertEquals(2, result.failures().size());
    }

}
