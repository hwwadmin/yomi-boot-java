package com.yomi.boot.java.base.model;

import com.yomi.boot.java.base.enums.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通用响应模型：默认码与分页语义
 */
class ResponseTest {

    @Test
    void successSetsCode200() {
        Response<String> r = Response.success("data");
        assertEquals(StatusCode.succeed.getCode(), r.getCode());
        assertEquals("data", r.getData());
        assertTrue(r.getTimestamp() > 0);
    }

    @Test
    void successWithoutData() {
        Response<Void> r = Response.success();
        assertEquals(StatusCode.succeed.getCode(), r.getCode());
    }

    @Test
    void failWithExplicitCode() {
        Response<Void> r = Response.fail(401, "TOKEN_EXPIRED", "登录已过期");
        assertEquals(401, r.getCode());
        assertEquals("TOKEN_EXPIRED", r.getErrorCode());
        assertEquals("登录已过期", r.getDebugMessage());
    }

    @Test
    void failDefaultsToBizErrorCode() {
        // 业务失败使用 bizError(601)
        Response<Void> r = Response.fail("E01", "失败");
        assertEquals(StatusCode.bizError.getCode(), r.getCode());
        assertEquals("E01", r.getErrorCode());
    }

    @Test
    void pageResponseCarriesDataAndTotal() {
        PageResponse<String> p = PageResponse.page(List.of("a", "b"), 99);
        assertEquals(List.of("a", "b"), p.getData());
        assertEquals(99, p.getTotal());
        assertEquals(StatusCode.succeed.getCode(), p.getCode());
    }

    @Test
    void pageResponseNullListBecomesEmpty() {
        PageResponse<String> p = PageResponse.page(null, 3);
        assertNotNull(p.getData());
        assertTrue(p.getData().isEmpty());
        assertEquals(3, p.getTotal());
    }

    @Test
    void pageResponseEmpty() {
        PageResponse<String> p = PageResponse.empty();
        assertTrue(p.getData().isEmpty());
        assertEquals(0, p.getTotal());
    }

    @Test
    void pageRequestDefaults() {
        PageRequest req = new PageRequest();
        assertEquals(PageRequest.DEFAULT_PAGE_NO, req.getPageNo());
        assertEquals(PageRequest.DEFAULT_PAGE_SIZE, req.getPageSize());
        assertEquals(1, PageRequest.DEFAULT_PAGE_NO);
        assertEquals(10, PageRequest.DEFAULT_PAGE_SIZE);
        assertEquals(500, PageRequest.MAX_PAGE_SIZE);
    }

}
