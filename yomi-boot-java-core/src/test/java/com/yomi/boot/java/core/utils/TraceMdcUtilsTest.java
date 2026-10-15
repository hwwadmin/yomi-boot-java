package com.yomi.boot.java.core.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceMdcUtils} 单元测试
 */
class TraceMdcUtilsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void currentTraceId_readsFromMdc() {
        MDC.put(TraceMdcUtils.TRACE_ID, "trace-1");
        MDC.put(TraceMdcUtils.SPAN_ID, "span-1");
        assertEquals("trace-1", TraceMdcUtils.currentTraceId());
        assertEquals("span-1", TraceMdcUtils.currentSpanId());
        assertTrue(TraceMdcUtils.hasTraceId());
    }

    @Test
    void runWithMdc_restoresPrevious() {
        MDC.put(TraceMdcUtils.TRACE_ID, "outer");
        Map<String, String> snapshot = Map.of(TraceMdcUtils.TRACE_ID, "inner", TraceMdcUtils.SPAN_ID, "s1");

        AtomicReference<String> seen = new AtomicReference<>();
        TraceMdcUtils.runWithMdc(snapshot, () -> seen.set(TraceMdcUtils.currentTraceId()));

        assertEquals("inner", seen.get());
        assertEquals("outer", TraceMdcUtils.currentTraceId());
    }

    @Test
    void wrap_propagatesMdcToAnotherThread() throws Exception {
        MDC.put(TraceMdcUtils.TRACE_ID, "parent-trace");
        MDC.put(TraceMdcUtils.SPAN_ID, "parent-span");

        AtomicReference<String> childTrace = new AtomicReference<>();
        AtomicReference<String> childSpan = new AtomicReference<>();

        Thread thread = new Thread(TraceMdcUtils.wrap(() -> {
            childTrace.set(TraceMdcUtils.currentTraceId());
            childSpan.set(TraceMdcUtils.currentSpanId());
        }));
        thread.start();
        thread.join();

        assertEquals("parent-trace", childTrace.get());
        assertEquals("parent-span", childSpan.get());
        assertEquals("parent-trace", TraceMdcUtils.currentTraceId());
    }

}
