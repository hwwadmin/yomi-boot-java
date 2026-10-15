package com.yomi.boot.java.component.httplog.desensitize;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.desensitize.IDesensitizeService;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脱敏编排：键名定位 + 调用 component-desensitize 门面
 * <p>
 * 组件只做"定位与编排"：键名 → 策略名的词表全部来自配置（{@code yomi.httplog.desensitize.key-types}，
 * 零内置词表）；键名匹配为写法宽松相等（大小写、驼峰/下划线/中划线差异被吸收），配什么命中什么；
 * 脱敏规则本身一律由 {@link IDesensitizeService#mask(String, String)} 执行。
 * <p>body 结构化脱敏支持 JSON 与 application/x-www-form-urlencoded；无法识别或无命中时原样返回。
 *
 * @author zwei
 */
@Slf4j
public class HttpLogDesensitizer {

    /** 脱敏策略门面 */
    private final IDesensitizeService desensitizeService;

    /** 归一化键名 → 策略名 */
    private final Map<String, String> keyTypes;

    /** 归一化排除键名（优先于命中） */
    private final Set<String> keyExcludes;

    /** 是否对 body 做结构化脱敏 */
    private final boolean bodyEnabled;

    /** 是否在请求线程内同步脱敏（否则延迟到发送线程） */
    private final boolean inRequestThread;

    public HttpLogDesensitizer(IDesensitizeService desensitizeService, HttpLogProperties.Desensitize properties) {
        this.desensitizeService = desensitizeService;
        Map<String, String> types = new LinkedHashMap<>();
        properties.getKeyTypes().forEach((key, type) -> types.put(normalize(key), type));
        this.keyTypes = Collections.unmodifiableMap(types);
        Set<String> excludes = new HashSet<>();
        properties.getKeyExcludes().forEach(key -> excludes.add(normalize(key)));
        this.keyExcludes = Collections.unmodifiableSet(excludes);
        this.bodyEnabled = properties.isBody();
        this.inRequestThread = properties.isInRequestThread();
    }

    /** 是否配置了任何生效映射（空词表时调用方跳过全部脱敏） */
    public boolean active() {
        return !keyTypes.isEmpty();
    }

    /** body 结构化脱敏是否开启 */
    public boolean bodyEnabled() {
        return bodyEnabled;
    }

    /** body 脱敏是否在请求线程执行（false=由后台线程补做） */
    public boolean inRequestThread() {
        return inRequestThread;
    }

    /** 头键值脱敏（请求头/响应头共用），返回新 Map 保持原顺序 */
    public Map<String, String> maskHeaders(Map<String, String> headers) {
        if (!active() || headers == null || headers.isEmpty()) {
            return headers;
        }
        Map<String, String> masked = new LinkedHashMap<>(headers.size());
        headers.forEach((name, value) -> {
            String type = match(name);
            masked.put(name, type == null ? value : desensitizeService.mask(type, value));
        });
        return masked;
    }

    /** query string 脱敏：k=v&k=v 原样结构，命中键的值替换 */
    public String maskQuery(String query) {
        if (!active() || StringUtils.isBlank(query)) {
            return query;
        }
        return maskKeyValuePairs(query);
    }

    /** body 结构化脱敏（JSON/form）；无法识别或无命中时原样返回 */
    public String maskBody(String body, String contentType) {
        if (!active() || StringUtils.isBlank(body)) {
            return body;
        }
        try {
            if (isJson(contentType, body)) {
                return maskJson(body);
            }
            if (isForm(contentType)) {
                return maskKeyValuePairs(body);
            }
        } catch (Exception e) {
            log.debug("[httplog]body 脱敏失败，原样记录: {}", e.getMessage());
        }
        return body;
    }

    /**
     * 后台补脱敏：请求线程未执行时，对 record 的请求体/响应体做结构化脱敏；
     * 被截断的 body 无法解析，保持原样（文档声明的脱敏盲区）
     */
    public void maskDeferredBodies(HttpLogRecord record) {
        if (inRequestThread || !bodyEnabled || !active()) {
            return;
        }
        if (record.getRequestBody() != null && !record.isRequestBodyTruncated()) {
            record.setRequestBody(maskBody(record.getRequestBody(), record.getRequestContentType()));
        }
        if (record.getResponseBody() != null && !record.isResponseBodyTruncated()) {
            record.setResponseBody(maskBody(record.getResponseBody(), record.getResponseContentType()));
        }
    }

    /** 键名匹配：归一化相等且未被排除 */
    private String match(String key) {
        String normalized = normalize(key);
        if (keyExcludes.contains(normalized)) {
            return null;
        }
        return keyTypes.get(normalized);
    }

    /** 归一化：小写并去掉 -/_/空格（idCard = id_card = ID-CARD = idcard） */
    static String normalize(String key) {
        if (key == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_' || c == ' ') {
                continue;
            }
            normalized.append(Character.toLowerCase(c));
        }
        return normalized.toString();
    }

    /** k=v&k=v 结构脱敏：键解码后匹配，值原样脱敏（保持编码结构） */
    private String maskKeyValuePairs(String text) {
        String[] segments = StringUtils.splitPreserveAllTokens(text, '&');
        boolean changed = false;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String rawKey = segment.substring(0, eq);
            String type = match(decode(rawKey));
            if (type == null) {
                continue;
            }
            String value = segment.substring(eq + 1);
            if (value.isEmpty()) {
                continue;
            }
            segments[i] = rawKey + "=" + desensitizeService.mask(type, value);
            changed = true;
        }
        return changed ? String.join("&", segments) : text;
    }

    /** 编解码 */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    /** 按 Content-Type 或 body 首字符判断是否为 JSON */
    private static boolean isJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            return true;
        }
        String trimmed = body.stripLeading();
        return !trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[');
    }

    /** Content-Type 是否为 application/x-www-form-urlencoded */
    private static boolean isForm(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("x-www-form-urlencoded");
    }

    /** JSON 树重建脱敏：仅命中才重新序列化（无命中返回原文） */
    private String maskJson(String body) {
        Object parsed;
        try {
            parsed = JsonUtils.parse(body, Object.class);
        } catch (Exception e) {
            return body;
        }
        if (parsed == null) {
            return body;
        }
        boolean[] changed = {false};
        Object masked = maskJsonValue(parsed, null, changed);
        return changed[0] ? JsonUtils.toJson(masked) : body;
    }

    /** 递归脱敏：Map 按字段名匹配；List 元素继承所在字段的策略（如 mobiles:[...]）；标量命中即替换 */
    private Object maskJsonValue(Object value, String type, boolean[] changed) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>(map.size());
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                masked.put(name, maskJsonValue(item, match(name), changed));
            });
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>(list.size());
            for (Object item : list) {
                masked.add(maskJsonValue(item, type, changed));
            }
            return masked;
        }
        if (type == null || value == null) {
            return value;
        }
        if (value instanceof String text) {
            changed[0] = true;
            return desensitizeService.mask(type, text);
        }
        if (value instanceof Number number) {
            changed[0] = true;
            return desensitizeService.mask(type, String.valueOf(number));
        }
        return value;
    }

}
