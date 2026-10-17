package com.yomi.boot.java.core.utils;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 链路 MDC 工具：读取当前 traceId/spanId，并支持手动跨线程传播。
 * <p>
 * {@code spring.task.execution.propagate-context} 只覆盖 Spring {@code @Async} 等自动线程池；
 * 自定义 {@code Thread}/{@code Executor} 用本类显式传播，任务结束后会还原调用前的上下文。
 *
 * <pre>{@code
 * executor.execute(TraceMdcUtils.wrap(() -> {
 *     log.info("async work, traceId={}", TraceMdcUtils.currentTraceId());
 * }));
 * }</pre>
 *
 * @author zwei
 */
public final class TraceMdcUtils {

    /** 日志跟踪ID（Micrometer Tracing MDC 键） */
    public static final String TRACE_ID = "traceId";

    /** 日志 Span ID（Micrometer Tracing MDC 键） */
    public static final String SPAN_ID = "spanId";

    /** Micrometer 上下文快照工厂 */
    private static final ContextSnapshotFactory SNAPSHOT_FACTORY = ContextSnapshotFactory.builder().build();

    /** 注册 SLF4J MDC 访问器，使 ContextSnapshot 可捕获/恢复 traceId */
    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    }

    private TraceMdcUtils() {
    }

    /**
     * 当前链路 ID（MDC {@link #TRACE_ID}），无则返回空串
     */
    public static String currentTraceId() {
        return StringUtils.defaultString(MDC.get(TRACE_ID));
    }

    /**
     * 当前 Span ID（MDC {@link #SPAN_ID}），无则返回空串
     */
    public static String currentSpanId() {
        return StringUtils.defaultString(MDC.get(SPAN_ID));
    }

    /**
     * 是否存在链路 ID
     */
    public static boolean hasTraceId() {
        return StringUtils.isNotBlank(MDC.get(TRACE_ID));
    }

    /**
     * 仅拷贝当前 MDC（轻量；保证日志可见）
     */
    public static Map<String, String> captureMdc() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        if (context == null || context.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(context));
    }

    /**
     * 在指定 MDC 下执行任务，结束后还原调用前 MDC
     */
    public static void runWithMdc(Map<String, String> context, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            applyMdc(context);
            task.run();
        } finally {
            applyMdc(previous);
        }
    }

    /**
     * 在指定 MDC 下执行任务并返回结果，结束后还原调用前 MDC
     */
    public static <T> T callWithMdc(Map<String, String> context, Callable<T> task) throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            applyMdc(context);
            return task.call();
        } finally {
            applyMdc(previous);
        }
    }

    /**
     * 捕获当前观测 + MDC 上下文快照
     */
    public static ContextSnapshot capture() {
        return SNAPSHOT_FACTORY.captureAll();
    }

    /**
     * 包装 Runnable：在调用线程捕获 MDC/观测上下文，在执行线程恢复，结束后清理
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> mdc = captureMdc();
        ContextSnapshot snapshot = capture();
        return () -> runPropagated(snapshot, mdc, task);
    }

    /**
     * 使用已捕获的快照与 MDC 包装 Runnable
     */
    public static Runnable wrap(ContextSnapshot snapshot, Map<String, String> mdc, Runnable task) {
        Map<String, String> frozen = freezeMdc(mdc);
        return () -> runPropagated(snapshot, frozen, task);
    }

    /**
     * 包装 Callable：在调用线程捕获上下文，在执行线程恢复，结束后清理
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> mdc = captureMdc();
        ContextSnapshot snapshot = capture();
        return () -> callPropagated(snapshot, mdc, task);
    }

    /**
     * 包装 Supplier（便于 {@code CompletableFuture.supplyAsync}）
     */
    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        Map<String, String> mdc = captureMdc();
        ContextSnapshot snapshot = capture();
        return () -> {
            try {
                return callPropagated(snapshot, mdc, supplier::get);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /**
     * 在快照上下文中执行 Runnable（含 MDC 强制对齐）
     */
    public static void run(ContextSnapshot snapshot, Runnable task) {
        runPropagated(snapshot, captureMdc(), task);
    }

    /**
     * 在快照上下文中执行 Callable（含 MDC 强制对齐）
     */
    public static <T> T call(ContextSnapshot snapshot, Callable<T> task) throws Exception {
        return callPropagated(snapshot, captureMdc(), task);
    }

    /** 在恢复的上下文中执行 Runnable */
    private static void runPropagated(ContextSnapshot snapshot, Map<String, String> mdc, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
            applyMdc(mdc);
            task.run();
        } finally {
            applyMdc(previous);
        }
    }

    /** 在恢复的上下文中执行 Callable */
    private static <T> T callPropagated(ContextSnapshot snapshot, Map<String, String> mdc, Callable<T> task)
            throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
            applyMdc(mdc);
            return task.call();
        } finally {
            applyMdc(previous);
        }
    }

    /** 冻结 MDC 上下文副本 */
    private static Map<String, String> freezeMdc(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<>(mdc));
    }

    /** 恢复 MDC 上下文 */
    private static void applyMdc(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

}
