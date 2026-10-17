package com.yomi.boot.java.qucik.starter.testexcel.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testexcel.dto.TestExcelImportResult;
import com.yomi.boot.java.qucik.starter.testexcel.service.TestExcelFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Excel 导入导出流程测试（Fesod 只钉版不封装组件，本包是业务侧胶水样板）
 * <ul>
 *     <li>POST /test/ds/test-excel/import：multipart 字段 {@code file}，导入错误逐行回显</li>
 *     <li>GET /test/ds/test-excel/export?pageSize=1000&amp;pageCount=5：分页流式导出</li>
 * </ul>
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-excel")
@RequiredArgsConstructor
public class TestExcelFlowController {

    /** 单页行数上限：演示桩同样服务端强制，超大步长会把应用拖挂 */
    private static final int MAX_PAGE_SIZE = 1000;

    /** 总页数上限（总导出量上限 = 1000 × 100 = 10 万行） */
    private static final int MAX_PAGE_COUNT = 100;

    /** Excel 导入导出演示 */
    private final TestExcelFlowService flowService;

    @PostMapping("/import")
    public Response<TestExcelImportResult> importRows(@RequestParam("file") MultipartFile file) throws IOException {
        // 演示桩省略了类型白名单与大小限制，真实导入接口应补上；流由 service 关闭
        return Response.success(flowService.importRows(file.getInputStream()));
    }

    @GetMapping("/export")
    public void export(@RequestParam(defaultValue = "1000") int pageSize,
                       @RequestParam(defaultValue = "5") int pageCount,
                       HttpServletResponse response) throws IOException {
        // 本路径免认证，上限由服务端强制；pageSize 给超大值会在 mockPage 里 OOM
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize 取值 1~" + MAX_PAGE_SIZE + "（当前 " + pageSize + "）");
        }
        if (pageCount < 1 || pageCount > MAX_PAGE_COUNT) {
            throw new IllegalArgumentException("pageCount 取值 1~" + MAX_PAGE_COUNT + "（当前 " + pageCount + "）");
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode("测试数据导出.xlsx", StandardCharsets.UTF_8));
        flowService.export(response.getOutputStream(), pageSize, pageCount);
    }

}
