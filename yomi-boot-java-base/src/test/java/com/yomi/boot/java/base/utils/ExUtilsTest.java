package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常工具：cause链遍历（含循环防护）与核心消息提取
 */
class ExUtilsTest {

    @Test
    void getMessage() {
        assertNull(ExUtils.getMessage(null));
        assertEquals("x", ExUtils.getMessage(new RuntimeException("x")));
    }

    @Test
    void stacktraceToString() {
        assertNull(ExUtils.stacktraceToString(null));
        String s = ExUtils.stacktraceToString(new RuntimeException("boom"));
        assertTrue(s.contains("java.lang.RuntimeException: boom"));
        assertTrue(s.length() <= 3000);
    }

    @Test
    void isCausedByTraversesCauseChain() {
        RuntimeException wrapped = new RuntimeException(new IllegalStateException("ise"));
        assertTrue(ExUtils.isCausedBy(wrapped, IllegalStateException.class));
        assertFalse(ExUtils.isCausedBy(wrapped, NumberFormatException.class));
        // 数组重载不是 varargs，需显式传数组
        assertTrue(ExUtils.isCausedBy(wrapped, new Class[]{IllegalStateException.class}));
        assertFalse(ExUtils.isCausedBy(wrapped, new Class[]{NumberFormatException.class}));
        // 空数组直接判否
        assertFalse(ExUtils.isCausedBy(wrapped, new Class[0]));
    }

    @Test
    void isCausedBySystemChecksRootCause() {
        assertTrue(ExUtils.isCausedBySystem(BizException.exception("biz")));
        assertTrue(ExUtils.isCausedBySystem(new RuntimeException(BizException.exception("biz"))));
        assertFalse(ExUtils.isCausedBySystem(new RuntimeException("plain")));
    }

    @Test
    void circularCauseChainTerminates() {
        RuntimeException a = new RuntimeException();
        RuntimeException b = new RuntimeException(a);
        a.initCause(b); // a -> b -> a 循环
        // 循环链上各方法都必须终止；b 的消息为 cause.toString()，无 ": " 时原样提取
        assertFalse(ExUtils.isCausedBy(a, NumberFormatException.class));
        assertFalse(ExUtils.isCausedBySystem(a));
        assertEquals(new RuntimeException().getClass().getName(), ExUtils.getCoreExceptionMsg(a));
    }

    @Test
    void coreExceptionMsgExtraction() {
        assertEquals("Operation failed", ExUtils.getCoreExceptionMsg(null));
        assertEquals("first", ExUtils.getCoreExceptionMsg(new RuntimeException("first\nsecond")));
        assertEquals("conn refused",
                ExUtils.getCoreExceptionMsg(new RuntimeException("java.sql.SQLException: conn refused")));
        assertEquals("plain", ExUtils.getCoreExceptionMsg(new RuntimeException("plain")));
        // 自身消息为空时使用 cause 的消息
        assertEquals("cause msg",
                ExUtils.getCoreExceptionMsg(new RuntimeException(new RuntimeException("cause msg"))));
    }

}
