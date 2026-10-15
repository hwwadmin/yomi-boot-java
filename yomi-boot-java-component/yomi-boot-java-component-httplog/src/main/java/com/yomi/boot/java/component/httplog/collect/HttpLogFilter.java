package com.yomi.boot.java.component.httplog.collect;

import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.queue.HttpLogSink;
import com.yomi.boot.java.component.httplog.support.HttpLogMetrics;
import com.yomi.boot.java.core.utils.TraceMdcUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 请求日志采集过滤器：双 tee 包装采集请求/响应（透传恒完整），组装后非阻塞投递；采集异常绝不外溢。
 * <p>async 请求在 ASYNC 分发结束时才采集（响应已写完、状态与耗时都是最终值）：首次分发只包装请求/响应并把
 * 起始时间、追踪号与包装器存进请求属性，ASYNC 分发复用同一对包装器——async 线程经包装 writer 写出的内容
 * 因此会随该分发结束时的 flush 一起落到真实响应，不会滞留在包装缓冲里。ERROR 分发（无 ASYNC 分发的异步
 * 异常路径也走它）不参与采集，与 FORWARD/INCLUDE 一样靠"已过滤"属性避免重复。
 *
 * @author zwei
 */
@Slf4j
public class HttpLogFilter extends OncePerRequestFilter {

    /** 跨分发采集上下文属性（async 请求由 ASYNC 分发取回） */
    private static final String CONTEXT_ATTRIBUTE = HttpLogFilter.class.getName() + ".CONTEXT";

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 组装 {@link HttpLogRecord} */
    private final HttpLogRecordFactory factory;

    /** 非阻塞投递目标（memory/redis/kafka） */
    private final HttpLogSink sink;

    /** 采集失败与队列满丢弃计数 */
    private final HttpLogMetrics metrics;

    /** 非空时仅采集匹配路径 */
    private final List<PathPattern> includePatterns;

    /** 命中则跳过采集 */
    private final List<PathPattern> excludePatterns;

    /** 命中则不记录请求/响应 body */
    private final List<PathPattern> bodySkipPatterns;

    public HttpLogFilter(HttpLogProperties properties, HttpLogRecordFactory factory,
                         HttpLogSink sink, HttpLogMetrics metrics) {
        this.properties = properties;
        this.factory = factory;
        this.sink = sink;
        this.metrics = metrics;
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.includePatterns = compile(parser, properties.getCollect().getIncludePaths());
        this.excludePatterns = compile(parser, properties.getCollect().getExcludePaths());
        this.bodySkipPatterns = compile(parser, properties.getCollect().getBodySkipPaths());
    }

    /** 未启用采集或命中排除路径时跳过过滤器 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (properties.getCollect().isSkipPreflight() && CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String path = resolvePath(request);
        if (matchesAny(excludePatterns, path)) {
            return true;
        }
        return !includePatterns.isEmpty() && !matchesAny(includePatterns, path);
    }

    /** 参与 ASYNC 分发（默认跳过）：async 响应在该分发才写完，采集与 flush 都要等到它结束 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CollectContext context = (CollectContext) request.getAttribute(CONTEXT_ATTRIBUTE);
        if (context == null) {
            if (isAsyncDispatch(request)) {
                // 首次分发未参与采集（路径被排除等）：ASYNC 分发不补采，避免只记到后半条
                chain.doFilter(request, response);
                return;
            }
            context = createContext(request, response);
            request.setAttribute(CONTEXT_ATTRIBUTE, context);
        }
        try {
            chain.doFilter(context.request, context.response);
        } finally {
            // async 起始时先落一次已写内容；ASYNC 分发结束时再 flush（async 线程可能经同一 writer 续写）
            flushWriterQuietly(context.response);
            if (!request.isAsyncStarted()) {
                collect(context);
            }
        }
    }

    /** 首次分发建立采集上下文：包装请求/响应并固化起始时间、追踪号与降量所需信息 */
    private CollectContext createContext(HttpServletRequest request, HttpServletResponse response) {
        HttpLogProperties.Collect collect = properties.getCollect();
        String path = resolvePath(request);
        boolean bodySkipped = matchesAny(bodySkipPatterns, path);
        int requestLimit = collect.isRecordRequestBody() && !bodySkipped ? collect.getMaxRequestBodyBytes() : 0;
        int responseLimit = collect.isRecordResponseBody() && !bodySkipped ? collect.getMaxResponseBodyBytes() : 0;
        return new CollectContext(path, bodySkipped, Instant.now(), TraceMdcUtils.currentTraceId(), System.nanoTime(),
                new CachingRequestWrapper(request, requestLimit), new CachingResponseWrapper(response, responseLimit));
    }

    /**
     * 业务经由包装 writer 写入的数据停留在其自身缓冲中，容器不会 flush 它——
     * 必须在读取缓存前 flush；失败只计数，绝不掩盖业务异常（finally 中再抛会替换原始异常）
     */
    private void flushWriterQuietly(CachingResponseWrapper response) {
        try {
            response.flushWriterIfCreated();
        } catch (Throwable t) {
            metrics.failed("response-flush", t);
        }
    }

    /** 组装并投递；一切异常吞掉并计数，绝不影响业务响应 */
    private void collect(CollectContext context) {
        try {
            int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - context.startNanos) / 1_000_000);
            int status = context.response.getStatus();
            if (!factory.shouldRecord(durationMs, status)) {
                return;
            }
            HttpLogRecord record = factory.build(context.request, context.response, context.path, context.traceId,
                    context.requestTime, durationMs, status, context.bodySkipped);
            if (!sink.offer(record)) {
                metrics.dropped("queue-full", 1);
            }
        } catch (Throwable t) {
            metrics.failed("collect", t);
        }
    }

    /** 请求路径（去掉 context-path） */
    private static String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 路径是否匹配任一 Ant 风格模式 */
    private static boolean matchesAny(List<PathPattern> patterns, String path) {
        if (patterns.isEmpty()) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern pattern : patterns) {
            if (pattern.matches(container)) {
                return true;
            }
        }
        return false;
    }

    /** 将配置中的路径字符串编译为 {@link PathPattern} */
    private static List<PathPattern> compile(PathPatternParser parser, List<String> patterns) {
        List<PathPattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            if (StringUtils.isNotBlank(pattern)) {
                compiled.add(parser.parse(pattern.trim()));
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * 采集上下文：一次请求一对包装器，async 请求跨分发沿用（请求体缓存、响应体缓存与 async 线程持有的
     * writer 都保持同一实例，flush 才能落到它身上）
     */
    private static final class CollectContext {

        /** 请求路径（去 context-path） */
        private final String path;

        /** 命中 bodySkipPaths */
        private final boolean bodySkipped;

        /** 请求进入时间 */
        private final Instant requestTime;

        /** 首次分发时的链路跟踪ID（ASYNC 分发线程可能没有 MDC） */
        private final String traceId;

        /** 首次分发起始纳秒（耗时含 async 等待） */
        private final long startNanos;

        /** tee 请求包装 */
        private final CachingRequestWrapper request;

        /** tee 响应包装 */
        private final CachingResponseWrapper response;

        private CollectContext(String path, boolean bodySkipped, Instant requestTime, String traceId,
                               long startNanos, CachingRequestWrapper request, CachingResponseWrapper response) {
            this.path = path;
            this.bodySkipped = bodySkipped;
            this.requestTime = requestTime;
            this.traceId = traceId;
            this.startNanos = startNanos;
            this.request = request;
            this.response = response;
        }

    }

}
