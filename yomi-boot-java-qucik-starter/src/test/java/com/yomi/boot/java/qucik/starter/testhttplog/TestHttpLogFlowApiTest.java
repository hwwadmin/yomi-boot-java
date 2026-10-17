package com.yomi.boot.java.qucik.starter.testhttplog;

import com.yomi.boot.java.base.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 日志流程接口：POST 触发请求 → query 概要取回 id → detail 取全量断言
 * "请求头/请求体/响应头/响应体全采 + 词表脱敏（含宿主自定义策略 EMPLOYEE_NO）"端到端成立；
 * 查询全程走组件服务接口 IHttpLogService
 * <p>
 * 运行前需手动启动应用（mvn -o spring-boot:run -pl yomi-boot-java-qucik-starter），
 * 并保证 sys_http_log 表已创建（组件启动期自动创建；脚本 sql/mysql|pgsql/sys_http_log.sql）
 *
 * @author zwei
 */
@Slf4j
class TestHttpLogFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String FLOW_PATH = "/test/ds/test-httplog/flow";
    private static final String ASYNC_PATH = "/test/ds/test-httplog/async-flow";
    private static final String QUERY_PATH = "/test/ds/test-httplog/query";
    private static final String DETAIL_PATH = "/test/ds/test-httplog/detail";

    private static final Duration INGEST_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void flowCapturesAndMasksRequestResponse() throws Exception {
        String marker = "httplog-" + System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("password", "abc123");
        request.put("mobile", "13812345678");
        request.put("employeeNo", "12345678");
        request.put("note", "plain-value");

        ResponseEntity<String> response = restClient.post()
                .uri(FLOW_PATH + "?marker=" + marker)
                .header(HttpHeaders.AUTHORIZATION, "Bearer demo-secret-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtils.toJson(request))
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "触发请求应成功: " + response.getBody());

        Map<String, Object> row = pollDetail(FLOW_PATH, marker);
        assertEquals(Boolean.TRUE, row.get("found"), "日志未在超时内入库（异步入库）");

        // 基础字段
        assertEquals("POST", row.get("method"));
        assertEquals("/test/ds/test-httplog/flow", row.get("uri"));
        assertEquals(200, ((Number) row.get("status")).intValue());
        assertNotNull(row.get("durationMs"));
        assertTrue(String.valueOf(row.get("queryString")).contains("marker=" + marker));
        assertEquals(Boolean.FALSE, row.get("requestBodyTruncated"));
        assertEquals(Boolean.FALSE, row.get("responseBodyTruncated"));

        // 请求头：全量采集 + authorization 按词表遮蔽
        String requestHeaders = String.valueOf(row.get("requestHeaders"));
        assertTrue(requestHeaders.contains("Authorization"), "请求头应全量采集: " + requestHeaders);
        assertFalse(requestHeaders.contains("demo-secret-token"), "authorization 应已遮蔽: " + requestHeaders);

        // 请求体：字段级脱敏（PASSWORD 固定 6 星 / MOBILE 前 3 后 4 / EMPLOYEE_NO 前 4 后 2）
        String requestBody = String.valueOf(row.get("requestBody"));
        assertTrue(requestBody.contains("\"password\":\"******\""), "密码应遮蔽: " + requestBody);
        assertTrue(requestBody.contains("138****5678"), "手机号应遮蔽: " + requestBody);
        assertTrue(requestBody.contains("1234**78"), "工号应按自定义策略(4,2)遮蔽: " + requestBody);
        assertTrue(requestBody.contains("plain-value"), "未命中键应原样保留: " + requestBody);
        assertFalse(requestBody.contains("13812345678"), "原始手机号不应入库");

        // 响应头：全量采集
        String responseHeaders = String.valueOf(row.get("responseHeaders"));
        assertTrue(responseHeaders.contains("Content-Type"), "响应头应全量采集: " + responseHeaders);

        // 响应体：token 遮蔽 + echo 嵌套字段递归遮蔽
        String responseBody = String.valueOf(row.get("responseBody"));
        assertTrue(responseBody.contains("echo"), "响应体应完整采集: " + responseBody);
        assertFalse(responseBody.contains("tok-demo-123456"), "token 应已遮蔽: " + responseBody);
        assertTrue(responseBody.contains("138****5678"), "响应体嵌套字段应递归遮蔽: " + responseBody);

        log.info("[HTTP日志流程] 断言通过 marker={}, id={}", marker, row.get("id"));
    }

    /**
     * 异步请求：Tomcat ASYNC 分发——客户端拿到完整响应体，日志记到最终状态/耗时/响应体
     */
    @Test
    void asyncFlowCapturesCompleteResponseBody() throws Exception {
        String marker = "httplog-async-" + System.currentTimeMillis();
        long delayMs = 80L;

        ResponseEntity<String> response = restClient.get()
                .uri(ASYNC_PATH + "?marker=" + marker + "&delayMs=" + delayMs)
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "异步请求应成功: " + response.getBody());
        assertTrue(response.getBody().contains(marker),
                "客户端应收到完整异步响应体（包装 writer 不丢数据）: " + response.getBody());

        Map<String, Object> row = pollDetail(ASYNC_PATH, marker);
        assertEquals(Boolean.TRUE, row.get("found"), "日志未在超时内入库（异步入库）");

        assertEquals(200, ((Number) row.get("status")).intValue());
        assertEquals(Boolean.TRUE, row.get("asyncStarted"), "异步请求应标记 async_started");
        assertTrue(((Number) row.get("durationMs")).longValue() >= delayMs,
                "耗时须覆盖 async 等待（采集点在 ASYNC 分发结束）: " + row.get("durationMs"));
        String responseBody = String.valueOf(row.get("responseBody"));
        assertTrue(responseBody.contains(marker), "响应体应完整采集: " + responseBody);
        assertFalse(responseBody.contains("tok-async-123456"), "token 应已遮蔽: " + responseBody);

        log.info("[HTTP日志流程] 异步断言通过 marker={}, id={}", marker, row.get("id"));
    }

    /** 轮询：query 概要取最近一条（校验 marker 归属）→ detail 取全量（含头/体） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pollDetail(String uriLike, String marker) throws InterruptedException {
        long deadline = System.nanoTime() + INGEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> queryResponse = restClient.get()
                    .uri(QUERY_PATH + "?uriLike=" + uriLike + "&pageSize=1")
                    .retrieve().toEntity(String.class);
            Map<String, Object> queryBody = JsonUtils.jsonStr2Map(queryResponse.getBody());
            Object data = queryBody == null ? null : queryBody.get("data");
            if (data instanceof List<?> list && !list.isEmpty()) {
                Map<String, Object> first = (Map<String, Object>) list.get(0);
                Object queryString = first.get("queryString");
                if (queryString != null && String.valueOf(queryString).contains("marker=" + marker)) {
                    Map<String, Object> detail = fetchDetail(first.get("id"));
                    if (detail != null) {
                        detail.put("found", true);
                        return detail;
                    }
                }
            }
            Thread.sleep(200);
        }
        return Map.of("found", false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchDetail(Object id) {
        ResponseEntity<String> detailResponse = restClient.get()
                .uri(DETAIL_PATH + "?id=" + id)
                .retrieve().toEntity(String.class);
        Map<String, Object> detailBody = JsonUtils.jsonStr2Map(detailResponse.getBody());
        return detailBody == null ? null : (Map<String, Object>) detailBody.get("data");
    }

}
