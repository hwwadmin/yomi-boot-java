package com.yomi.boot.java.component.httplog.collect;

import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.desensitize.HttpLogDesensitizer;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 记录组装：路径外信息提取、截断、头/query 脱敏（请求线程）、扩展点填充
 *
 * @author zwei
 */
@Slf4j
public class HttpLogRecordFactory {

    /** 请求头单值最大长度 */
    private static final int MAX_HEADER_VALUE = 1024;
    /** HTTP 方法列最大长度 */
    private static final int MAX_METHOD = 10;
    /** URI 列最大长度 */
    private static final int MAX_URI = 500;
    /** Query 列最大长度 */
    private static final int MAX_QUERY = 1000;
    /** 客户端 IP 列最大长度 */
    private static final int MAX_CLIENT_IP = 64;
    /** traceId 列最大长度 */
    private static final int MAX_TRACE_ID = 64;
    /** 错误信息列最大长度 */
    private static final int MAX_ERROR_MSG = 1000;

    /** 模块配置 */
    private final HttpLogProperties properties;

    /** 服务名与 Redis 键等上下文 */
    private final HttpLogKeys keys;

    /** 头、Query、Body 脱敏 */
    private final HttpLogDesensitizer desensitizer;

    /** 扩展字段填充（可选 Bean 列表） */
    private final List<HttpLogContextEnricher> enrichers;

    public HttpLogRecordFactory(HttpLogProperties properties, HttpLogKeys keys,
                                HttpLogDesensitizer desensitizer, List<HttpLogContextEnricher> enrichers) {
        this.properties = properties;
        this.keys = keys;
        this.desensitizer = desensitizer;
        this.enrichers = enrichers == null ? List.of() : enrichers;
    }

    /**
     * 降量闸门：采样随机 + "只记慢请求/错误状态"（两者都开启时满足其一即记）
     */
    public boolean shouldRecord(int durationMs, int status) {
        HttpLogProperties.Collect collect = properties.getCollect();
        if (collect.getSampleRate() < 1.0
                && ThreadLocalRandom.current().nextDouble() >= collect.getSampleRate()) {
            return false;
        }
        boolean gateSlow = collect.getMinDurationMs() > 0;
        boolean gateStatus = collect.getStatusThreshold() > 0;
        if (!gateSlow && !gateStatus) {
            return true;
        }
        return (gateSlow && durationMs >= collect.getMinDurationMs())
                || (gateStatus && status >= collect.getStatusThreshold());
    }

    /** 组装记录（本方法内的异常由调用方处理，业务响应不受影响） */
    public HttpLogRecord build(CachingRequestWrapper request, CachingResponseWrapper response,
                               String path, String traceId, Instant requestTime,
                               int durationMs, int status, boolean bodySkipped) {
        HttpLogProperties.Collect collect = properties.getCollect();
        HttpLogRecord record = HttpLogRecord.builder()
                .id(IdUtils.nextId())
                .serviceName(keys.getServiceName())
                .traceId(truncate(traceId, MAX_TRACE_ID))
                .requestTime(requestTime)
                .durationMs(durationMs)
                .method(request.getMethod())
                .uri(truncate(path, MAX_URI))
                .queryString(truncate(desensitizeQuery(request.getQueryString()), MAX_QUERY))
                .status(status)
                .clientIp(truncate(resolveClientIp(request, collect.getTrustedProxies()), MAX_CLIENT_IP))
                // ASYNC 分发即异步请求：采集发生在该分发结束，此刻 isAsyncStarted() 已为 false
                .asyncStarted(DispatcherType.ASYNC.equals(request.getDispatcherType()))
                .build();

        if (collect.isRecordRequestHeaders()) {
            record.setRequestHeaders(toJson(desensitizeHeaders(collectHeaders(request, collect.getHeaderIgnore()))));
        }
        if (collect.isRecordResponseHeaders()) {
            record.setResponseHeaders(toJson(desensitizeHeaders(response.cachedHeaders())));
        }
        if (!bodySkipped) {
            record.setRequestContentType(request.getContentType());
            record.setResponseContentType(response.getContentType());
            if (collect.isRecordRequestBody() && bodyRecordable(request.getContentType())) {
                record.setRequestBody(request.cachedBody());
                record.setRequestBodyTruncated(request.cachedBody() != null && request.isTruncated());
            }
            if (collect.isRecordResponseBody() && bodyRecordable(response.getContentType())) {
                record.setResponseBody(response.cachedBody());
                record.setResponseBodyTruncated(response.cachedBody() != null && response.isTruncated());
            }
        }
        if (desensitizeEnabled() && desensitizer.bodyEnabled() && desensitizer.inRequestThread()) {
            maskBodyInPlace(record);
        }
        applyEnrichers(request, response, record);
        applyColumnLimits(record);
        return record;
    }

    /** 列宽截断：任何来源（异常 method、enricher 超长填充）都截到 sys_http_log 列宽内，超宽会使整批插入失败丢弃 */
    private static void applyColumnLimits(HttpLogRecord record) {
        record.setTraceId(truncate(record.getTraceId(), MAX_TRACE_ID));
        record.setMethod(truncate(record.getMethod(), MAX_METHOD));
        record.setUri(truncate(record.getUri(), MAX_URI));
        record.setQueryString(truncate(record.getQueryString(), MAX_QUERY));
        record.setClientIp(truncate(record.getClientIp(), MAX_CLIENT_IP));
        record.setErrorMsg(truncate(record.getErrorMsg(), MAX_ERROR_MSG));
    }

    /** 二进制/流式内容不记 body（仅透传不动）；其余按文本记录（JSON/form 判断在脱敏侧） */
    private static boolean bodyRecordable(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String lower = contentType.toLowerCase();
        return !(lower.startsWith("multipart/")
                || lower.startsWith("image/")
                || lower.startsWith("audio/")
                || lower.startsWith("video/")
                || lower.contains("octet-stream")
                || lower.contains("text/event-stream")
                || lower.contains("application/pdf")
                || lower.contains("application/zip")
                || lower.contains("application/gzip"));
    }

    /** 请求线程侧 body 脱敏（in-request-thread=true） */
    private void maskBodyInPlace(HttpLogRecord record) {
        if (record.getRequestBody() != null && !record.isRequestBodyTruncated()) {
            record.setRequestBody(desensitizer.maskBody(record.getRequestBody(), record.getRequestContentType()));
        }
        if (record.getResponseBody() != null && !record.isResponseBodyTruncated()) {
            record.setResponseBody(desensitizer.maskBody(record.getResponseBody(), record.getResponseContentType()));
        }
    }

    /** 是否启用脱敏配置 */
    private boolean desensitizeEnabled() {
        return properties.getDesensitize().isEnabled();
    }

    /** 按配置脱敏请求/响应头 */
    private Map<String, String> desensitizeHeaders(Map<String, String> headers) {
        return desensitizeEnabled() ? desensitizer.maskHeaders(headers) : headers;
    }

    /** 按配置脱敏 Query 字符串 */
    private String desensitizeQuery(String query) {
        return desensitizeEnabled() ? desensitizer.maskQuery(query) : query;
    }

    /** 采集请求头（忽略名单过滤、单值截断、同名多值合并） */
    private static Map<String, String> collectHeaders(HttpServletRequest request, List<String> ignores) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || containsIgnoreCase(ignores, name)) {
                continue;
            }
            String value = StringUtils.join(Collections.list(request.getHeaders(name)), ", ");
            headers.put(name, truncate(value, MAX_HEADER_VALUE));
        }
        return headers;
    }

    /** 头名列表中是否包含给定名称（忽略大小写） */
    private static boolean containsIgnoreCase(List<String> values, String target) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    /** 客户端IP：默认 remoteAddr；remoteAddr 命中可信代理时才取 X-Forwarded-For 中最左侧非可信地址 */
    private static String resolveClientIp(HttpServletRequest request, List<String> trustedProxies) {
        String remote = toEmpty(request.getRemoteAddr());
        if (trustedProxies.isEmpty() || !trustedProxies.contains(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isBlank(forwarded)) {
            return remote;
        }
        for (String part : StringUtils.split(forwarded, ',')) {
            String ip = part.trim();
            if (!ip.isEmpty() && !trustedProxies.contains(ip)) {
                return ip;
            }
        }
        return remote;
    }

    /** 依次调用扩展点填充记录（单个失败仅 debug 日志） */
    private void applyEnrichers(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                                HttpLogRecord record) {
        for (HttpLogContextEnricher enricher : enrichers) {
            try {
                enricher.enrich(request, response, record);
            } catch (Exception e) {
                log.debug("[httplog]扩展点 {} 填充失败: {}", enricher.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** 头 Map 转 JSON，空 Map 返回 null */
    private static String toJson(Map<String, String> headers) {
        return headers.isEmpty() ? null : JsonUtils.toJson(headers);
    }

    /** 截断到列宽内（null 原样返回） */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** null 转空串 */
    private static String toEmpty(String value) {
        return value == null ? "" : value;
    }

}
