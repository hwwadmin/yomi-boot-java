package com.yomi.boot.java.qucik.starter.testexcel.service;

import com.yomi.boot.java.qucik.starter.testexcel.dto.TestExcelImportResult;
import com.yomi.boot.java.qucik.starter.testexcel.dto.TestExcelRow;
import com.yomi.boot.java.qucik.starter.testexcel.listener.TestExcelImportListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 导入导出胶水样板（Apache Fesod 直接用，不封装组件）
 * <p>
 * 导入：监听器收集成功行与错误行，整体返回逐行回显；
 * 导出：分页查数分批 write 同一个 sheet，内存里只保留单页数据。
 *
 * @author zwei
 */
@Slf4j
@Service
public class TestExcelFlowService {

    /** 上传 Excel 并解析为行列表 */
    public TestExcelImportResult importRows(InputStream in) {
        try (in) {
            var listener = new TestExcelImportListener();
            FesodSheet.read(in, TestExcelRow.class, listener).sheet().headRowNumber(1).doRead();
            int total = listener.rows().size() + listener.errors().size();
            log.info("[Excel导入] 完成, total={}, success={}, failed={}",
                    total, listener.rows().size(), listener.errors().size());
            return new TestExcelImportResult(total, listener.rows().size(), listener.errors());
        } catch (Exception e) {
            log.warn("[Excel导入] 失败, msg={}", e.getMessage());
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    /** 按演示数据生成导出文件 */
    public void export(OutputStream out, int pageSize, int pageCount) {
        try (ExcelWriter writer = FesodSheet.write(out, TestExcelRow.class).build()) {
            var sheet = FesodSheet.writerSheet(0, "测试数据").build();
            for (int page = 1; page <= pageCount; page++) {
                writer.write(mockPage(page, pageSize), sheet);
            }
        }
    }

    /** 演示桩：真实业务这里是 MyBatis 分页查询 */
    private List<TestExcelRow> mockPage(int page, int pageSize) {
        var rows = new ArrayList<TestExcelRow>(pageSize);
        for (int i = 1; i <= pageSize; i++) {
            var row = new TestExcelRow();
            row.setName("用户-" + page + "-" + i);
            row.setAge(20 + (i % 30));
            row.setEmail("user" + page + "_" + i + "@example.com");
            row.setStatus(i % 2);
            rows.add(row);
        }
        return rows;
    }

}
