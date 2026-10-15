package com.yomi.boot.java.component.httplog.collect;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双 tee 包装测试：透传恒完整 + 旁路缓存 + 超限截断只影响日志副本
 *
 * @author zwei
 */
class CachingWrapperTest {

    @Test
    void requestWrapper_passesThroughAndCaches() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/demo");
        request.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        CachingRequestWrapper wrapper = new CachingRequestWrapper(request, 1024);

        byte[] read = wrapper.getInputStream().readAllBytes();
        assertEquals("{\"a\":1}", new String(read, StandardCharsets.UTF_8));
        assertEquals("{\"a\":1}", wrapper.cachedBody());
        assertFalse(wrapper.isTruncated());
    }

    @Test
    void requestWrapper_truncatesCacheButPassThroughComplete() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/demo");
        request.setContent("0123456789".getBytes(StandardCharsets.UTF_8));
        CachingRequestWrapper wrapper = new CachingRequestWrapper(request, 4);

        byte[] read = wrapper.getInputStream().readAllBytes();
        assertEquals(10, read.length);
        assertEquals("0123", wrapper.cachedBody());
        assertTrue(wrapper.isTruncated());
    }

    @Test
    void requestWrapper_limitZero_cachesNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/demo");
        request.setContent("abc".getBytes(StandardCharsets.UTF_8));
        CachingRequestWrapper wrapper = new CachingRequestWrapper(request, 0);

        assertEquals("abc", new String(wrapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(null, wrapper.cachedBody());
        assertTrue(wrapper.isTruncated());
    }

    @Test
    void responseWrapper_writePassThroughAndCache() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        wrapper.getWriter().write("{\"ok\":true}");
        wrapper.getWriter().flush();

        // 客户端内容完整（tee：先写真实响应再写缓存）
        assertEquals("{\"ok\":true}", response.getContentAsString());
        assertEquals("{\"ok\":true}", wrapper.cachedBody());
        assertFalse(wrapper.isTruncated());
    }

    @Test
    void responseWrapper_truncatesCacheButPassThroughComplete() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 3);

        wrapper.getOutputStream().write("0123456789".getBytes(StandardCharsets.UTF_8));
        wrapper.getOutputStream().flush();

        assertEquals("0123456789", response.getContentAsString());
        assertEquals("012", wrapper.cachedBody());
        assertTrue(wrapper.isTruncated());
    }

    @Test
    void responseWrapper_recordsHeaders() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 0);

        wrapper.setHeader("Content-Type", "application/json");
        wrapper.addHeader("Set-Cookie", "a=1");
        wrapper.addHeader("Set-Cookie", "b=2");

        assertEquals("application/json", wrapper.cachedHeaders().get("Content-Type"));
        assertEquals("a=1, b=2", wrapper.cachedHeaders().get("Set-Cookie"));
        // 透传（MockHttpServletResponse#getHeader 返回首个值）
        assertEquals("application/json", response.getHeader("Content-Type"));
        assertEquals("a=1", response.getHeader("Set-Cookie"));
    }

    @Test
    void responseWrapper_writerWithoutExplicitFlush_flushWriterIfCreatedCompletes() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        // 模拟"用 writer 且不显式 flush"的渲染器（如 FreeMarker）：数据尚在包装 writer 缓冲中
        wrapper.getWriter().write("hello");
        assertEquals("", response.getContentAsString());

        wrapper.flushWriterIfCreated();
        assertEquals("hello", response.getContentAsString(), "flush 后透传完整（修复响应丢失）");
        assertEquals("hello", wrapper.cachedBody(), "且日志缓存完整");
    }

    @Test
    void responseWrapper_flushBuffer_flushesWriterFirst() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        wrapper.getWriter().write("ok");
        wrapper.flushBuffer();

        assertEquals("ok", response.getContentAsString());
        assertTrue(response.isCommitted());
    }

    @Test
    void responseWrapper_jsonWithoutCharset_decodesAsUtf8() throws Exception {
        MockHttpServletResponse response = tomcatLikeResponse("application/json");
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        wrapper.getOutputStream().write("{\"name\":\"张三\"}".getBytes(StandardCharsets.UTF_8));
        wrapper.getOutputStream().flush();

        assertEquals("application/json", response.getContentType());
        assertEquals("ISO-8859-1", response.getCharacterEncoding());
        assertEquals("{\"name\":\"张三\"}", wrapper.cachedBody(), "JSON 无 charset 应按 UTF-8 解码，中文不得乱码");
    }

    @Test
    void responseWrapper_vendorJsonWithoutCharset_decodesAsUtf8() throws Exception {
        MockHttpServletResponse response = tomcatLikeResponse("application/vnd.yomi+json");
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        wrapper.getOutputStream().write("{\"msg\":\"中文\"}".getBytes(StandardCharsets.UTF_8));
        wrapper.getOutputStream().flush();

        assertEquals("{\"msg\":\"中文\"}", wrapper.cachedBody(), "application/*+json 同样按 UTF-8 解码");
    }

    @Test
    void responseWrapper_declaredCharsetWins() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        wrapper.setContentType("application/json;charset=GBK");
        wrapper.getOutputStream().write("{\"name\":\"张三\"}".getBytes("GBK"));
        wrapper.getOutputStream().flush();

        assertEquals("{\"name\":\"张三\"}", wrapper.cachedBody(), "Content-Type 显式声明的 charset 优先");
    }

    @Test
    void responseWrapper_nonJsonWithoutCharset_keepsContainerCharset() throws Exception {
        MockHttpServletResponse response = tomcatLikeResponse("text/html");
        CachingResponseWrapper wrapper = new CachingResponseWrapper(response, 1024);

        // 非 JSON、容器按 ISO-8859-1 写：按 Latin-1 解码才对（不得一律当 UTF-8）
        wrapper.getOutputStream().write("café".getBytes(StandardCharsets.ISO_8859_1));
        wrapper.getOutputStream().flush();

        assertEquals("café", wrapper.cachedBody(), "非 JSON 无 charset 时沿用容器字符集");
    }

    /**
     * 复现真实容器行为（Tomcat 11 实测）：Content-Type 不带 charset，但 {@code getCharacterEncoding()} 报 ISO-8859-1。
     * <p>Spring 的 {@code MockHttpServletResponse} 默认报 UTF-8，与真实容器不同，故这里显式覆写。</p>
     */
    private static MockHttpServletResponse tomcatLikeResponse(String contentType) {
        return new MockHttpServletResponse() {
            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public String getCharacterEncoding() {
                return "ISO-8859-1";
            }
        };
    }

}
