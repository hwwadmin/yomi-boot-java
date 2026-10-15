package com.yomi.boot.java.component.httplog.collect;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记录组装测试：列宽兜底截断（异常 method / enricher 超长）与二进制内容跳过
 *
 * @author zwei
 */
class HttpLogRecordFactoryTest {

    /** 组件测试无 AppBoot，IdWorkerEnvironmentListener 不注册，需手工初始化雪花 */
    @BeforeAll
    static void initIdWorker() {
        IdUtils.initialize(1L, 0L, null);
    }

    @Test
    void build_truncatesOverlongMethodToColumnWidth() {
        MockHttpServletRequest request = new MockHttpServletRequest("VERY-LONG-METHOD", "/demo/flow");
        HttpLogRecord record = build(request, new MockHttpServletResponse(), List.of());

        assertEquals("VERY-LONG-", record.getMethod());
        assertEquals(10, record.getMethod().length());
        assertEquals("test", record.getServiceName());
        assertEquals("/demo/flow", record.getUri());
    }

    @Test
    void build_skipsBinaryResponseBodyButKeepsHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/download");

        CachingRequestWrapper requestWrapper = new CachingRequestWrapper(request, 1024);
        CachingResponseWrapper responseWrapper = new CachingResponseWrapper(response, 1024);
        // 经包装设置（真实链上业务也是通过它设置，采集才能记录）
        responseWrapper.setContentType("application/octet-stream");
        responseWrapper.getOutputStream().write(new byte[]{1, 2, 3});
        responseWrapper.getOutputStream().flush();

        HttpLogRecord record = factory(List.of()).build(requestWrapper, responseWrapper, "/download",
                "trace-1", Instant.now(), 5, 200, false);

        assertNull(record.getResponseBody(), "二进制响应不记 body");
        assertNotNull(record.getResponseHeaders(), "响应头照记");
        assertTrue(record.getResponseHeaders().contains("octet-stream"), record.getResponseHeaders());
    }

    @Test
    void build_keepsTextFieldResponseBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        CachingRequestWrapper requestWrapper = new CachingRequestWrapper(
                new MockHttpServletRequest("GET", "/demo"), 1024);
        CachingResponseWrapper responseWrapper = new CachingResponseWrapper(response, 1024);
        responseWrapper.setContentType("application/json");
        responseWrapper.getOutputStream().write("{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        responseWrapper.getOutputStream().flush();

        HttpLogRecord record = factory(List.of()).build(requestWrapper, responseWrapper, "/demo",
                "trace-1", Instant.now(), 5, 200, false);
        assertEquals("{\"ok\":true}", record.getResponseBody());
    }

    @Test
    void build_appliesColumnLimitsAfterEnrichers() {
        HttpLogContextEnricher enricher = (request, response, record) -> record.setErrorMsg("x".repeat(5000));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo");
        HttpLogRecord record = build(request, new MockHttpServletResponse(), List.of(enricher));

        assertEquals(1000, record.getErrorMsg().length(), "enricher 超长填充必须按列宽兜底");
    }

    private static HttpLogRecord build(MockHttpServletRequest request, MockHttpServletResponse response,
                                       List<HttpLogContextEnricher> enrichers) {
        CachingRequestWrapper requestWrapper = new CachingRequestWrapper(request, 1024);
        CachingResponseWrapper responseWrapper = new CachingResponseWrapper(response, 1024);
        return factory(enrichers).build(requestWrapper, responseWrapper, request.getRequestURI(),
                "trace-1", Instant.now(), 1, 200, false);
    }

    private static HttpLogRecordFactory factory(List<HttpLogContextEnricher> enrichers) {
        HttpLogProperties properties = new HttpLogProperties();
        HttpLogProperties.Desensitize desensitize = new HttpLogProperties.Desensitize();
        desensitize.setKeyTypes(Map.of());
        HttpLogKeys keys = new HttpLogKeys(properties,
                new MockEnvironment().withProperty("spring.application.name", "test"));
        HttpLogDesensitizer desensitizer = new HttpLogDesensitizer((type, value) -> value, desensitize);
        return new HttpLogRecordFactory(properties, keys, desensitizer, enrichers);
    }

}
