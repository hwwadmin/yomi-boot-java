package com.yomi.boot.java.base.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常体系：默认响应码与消息格式化
 */
class ExceptionsTest {

    @Test
    void bizExceptionDefaults() {
        BizException e = BizException.exception("下单失败");
        assertEquals(StatusCode.bizError.getCode(), e.getCode());
        assertEquals("UNKNOWN_BIZ_CODE", e.getErrorCode());
        assertEquals("下单失败", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void bizExceptionWithErrorCodeAndFormat() {
        BizException e = BizException.exception("STOCK_SHORT", "库存不足: sku={}", 1001L);
        assertEquals("STOCK_SHORT", e.getErrorCode());
        assertEquals("库存不足: sku=1001", e.getMessage());
    }

    @Test
    void bizExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root");
        BizException e = BizException.exception(cause, "E01", "处理失败: {}", "a");
        assertSame(cause, e.getCause());
        assertEquals("处理失败: a", e.getMessage());
        assertEquals("E01", e.getErrorCode());
    }

    @Test
    void blankErrorCodeFallsBackToUnknown() {
        BizException e = BizException.exception(" ", "msg");
        assertEquals("UNKNOWN_BIZ_CODE", e.getErrorCode());
    }

    @Test
    void frameworkExceptionCodes() {
        assertEquals(StatusCode.assertError.getCode(), AssertException.exception("x").getCode());
        assertEquals(StatusCode.utilsError.getCode(), UtilsException.exception("x").getCode());
        assertEquals(StatusCode.notImplemented.getCode(), NotImplException.exception().getCode());
        assertEquals("The function has not been implemented.", NotImplException.exception().getMessage());
    }

    @Test
    void assertExceptionMessageFormat() {
        AssertException e = AssertException.exception("参数 {} 非法: {}", "id", -1);
        assertEquals("参数 id 非法: -1", e.getMessage());
    }

    @Test
    void exceptionWithCauseKeepsCause() {
        RuntimeException cause = new IllegalStateException("state");
        AssertException e = AssertException.exception(cause, "断言失败");
        assertSame(cause, e.getCause());
        assertEquals(StatusCode.assertError.getCode(), e.getCode());
    }

    @Test
    void utilsExceptionMessageFormat() {
        UtilsException e = UtilsException.exception(new RuntimeException("boom"), "解析 {} 失败", "json");
        assertEquals("解析 json 失败", e.getMessage());
        assertEquals(StatusCode.utilsError.getCode(), e.getCode());
    }

}
