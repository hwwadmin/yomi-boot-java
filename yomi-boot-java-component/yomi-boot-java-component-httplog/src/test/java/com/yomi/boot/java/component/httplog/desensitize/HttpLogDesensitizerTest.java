package com.yomi.boot.java.component.httplog.desensitize;

import com.yomi.boot.java.component.desensitize.IDesensitizeService;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategyRegistry;
import com.yomi.boot.java.component.desensitize.service.DesensitizeService;
import com.yomi.boot.java.component.desensitize.strategy.AllDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.IdCardDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.MobileDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.strategy.PasswordDesensitizeStrategy;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏编排测试：键名匹配、结构化遍历、无命中免重建、盲区行为
 * <p>策略用 component-desensitize 的真实实现（DesensitizeService + 注册中心）。
 *
 * @author zwei
 */
class HttpLogDesensitizerTest {

    @Test
    void maskHeaders_typeBeforeValue_orderLocked() {
        // 参数顺序锁死：mask(策略名, 值)；若写反，"abc123" 会被当作策略名抛未注册
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        Map<String, String> headers = desensitizer.maskHeaders(Map.of("password", "abc123"));
        assertEquals("******", headers.get("password"));
    }

    @Test
    void maskHeaders_looseKeyMatching() {
        // 配 idCard 命中 id_card（大小写/驼峰/下划线差异由匹配层吸收）
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("idCard", "ID_CARD"), List.of());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("id_card", "110101199003074567");
        assertEquals("110101********4567", desensitizer.maskHeaders(headers).get("id_card"));
    }

    @Test
    void maskHeaders_wholeValueMaskedForCredentialKeys() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("authorization", "ALL"), List.of());
        Map<String, String> headers = Map.of("Authorization", "Bearer abcdef");
        // ALL 策略保留长度
        assertEquals("*".repeat("Bearer abcdef".length()), desensitizer.maskHeaders(headers).get("Authorization"));
    }

    @Test
    void maskQuery_masksMatchedValueOnly() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("mobile", "MOBILE"), List.of());
        assertEquals("mobile=138****5678&page=1", desensitizer.maskQuery("mobile=13812345678&page=1"));
    }

    @Test
    void maskBody_jsonNestedAndArrayInheritsFieldType() {
        HttpLogDesensitizer desensitizer = desensitizer(
                Map.of("password", "PASSWORD", "mobiles", "MOBILE"), List.of());
        String body = "{\"user\":{\"password\":\"abc123\"},\"mobiles\":[\"13812345678\",\"13912345678\"],\"page\":1}";
        String masked = desensitizer.maskBody(body, "application/json");
        assertTrue(masked.contains("\"password\":\"******\""), masked);
        assertTrue(masked.contains("138****5678"), masked);
        assertTrue(masked.contains("139****5678"), masked);
        assertTrue(masked.contains("1"), masked);
    }

    @Test
    void maskBody_jsonNumberValue_maskedAsText() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("mobile", "MOBILE"), List.of());
        String masked = desensitizer.maskBody("{\"mobile\":13812345678}", "application/json");
        assertTrue(masked.contains("138****5678"), masked);
    }

    @Test
    void maskBody_noHit_returnsOriginalInstance() {
        // 无命中免重建：原样返回（不做序列化）
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        String body = "{ \"page\" : 1 }";
        assertSame(body, desensitizer.maskBody(body, "application/json"));
    }

    @Test
    void maskBody_formUrlencoded() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        assertEquals("user=a&password=******",
                desensitizer.maskBody("user=a&password=abc123", "application/x-www-form-urlencoded"));
    }

    @Test
    void maskBody_nonJsonOrForm_returnsOriginal() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        String xml = "<password>abc</password>";
        assertEquals(xml, desensitizer.maskBody(xml, "application/xml"));
    }

    @Test
    void maskBody_invalidJson_returnsOriginal() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        String broken = "{\"password\":\"abc";
        assertEquals(broken, desensitizer.maskBody(broken, "application/json"));
    }

    @Test
    void keyExcludes_winsOverHit() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("phone", "MOBILE"), List.of("phoneModel"));
        Map<String, String> headers = Map.of("phoneModel", "iPhone15");
        assertEquals("iPhone15", desensitizer.maskHeaders(headers).get("phoneModel"));
    }

    @Test
    void emptyKeyTypes_notActive_returnsOriginal() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of(), List.of());
        assertFalse(desensitizer.active());
        String body = "{\"password\":\"abc123\"}";
        assertEquals(body, desensitizer.maskBody(body, "application/json"));
    }

    @Test
    void maskDeferredBodies_skipsTruncatedBody() {
        // 截断体无法解析 → 原样保留（文档声明的脱敏盲区）
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        HttpLogRecord record = HttpLogRecord.builder()
                .requestBody("{\"password\":\"abc1")
                .requestBodyTruncated(true)
                .build();
        desensitizer.maskDeferredBodies(record);
        assertEquals("{\"password\":\"abc1", record.getRequestBody());
    }

    @Test
    void maskDeferredBodies_skipsWhenInRequestThread() {
        // in-request-thread=true：请求线程已处理，后台不重复处理
        HttpLogProperties.Desensitize desensitize = new HttpLogProperties.Desensitize();
        desensitize.setKeyTypes(Map.of("password", "PASSWORD"));
        desensitize.setInRequestThread(true);
        HttpLogDesensitizer desensitizer = new HttpLogDesensitizer(service(), desensitize);
        HttpLogRecord record = HttpLogRecord.builder().requestBody("{\"password\":\"abc123\"}").build();
        desensitizer.maskDeferredBodies(record);
        assertEquals("{\"password\":\"abc123\"}", record.getRequestBody());
    }

    @Test
    void maskDeferredBodies_masksRequestAndResponse() {
        HttpLogDesensitizer desensitizer = desensitizer(Map.of("password", "PASSWORD"), List.of());
        HttpLogRecord record = HttpLogRecord.builder()
                .requestBody("{\"password\":\"abc123\"}")
                .responseBody("{\"password\":\"def456\"}")
                .build();
        desensitizer.maskDeferredBodies(record);
        assertEquals("{\"password\":\"******\"}", record.getRequestBody());
        assertEquals("{\"password\":\"******\"}", record.getResponseBody());
    }

    /** 构造编排器（body 开、后台线程脱敏） */
    private static HttpLogDesensitizer desensitizer(Map<String, String> keyTypes, List<String> excludes) {
        HttpLogProperties.Desensitize desensitize = new HttpLogProperties.Desensitize();
        desensitize.setKeyTypes(keyTypes);
        desensitize.setKeyExcludes(excludes);
        return new HttpLogDesensitizer(service(), desensitize);
    }

    /** 真实脱敏服务：4 个内置策略 */
    private static IDesensitizeService service() {
        Map<String, DesensitizeStrategy> strategies = new LinkedHashMap<>();
        strategies.put("mobile", new MobileDesensitizeStrategy());
        strategies.put("password", new PasswordDesensitizeStrategy());
        strategies.put("all", new AllDesensitizeStrategy());
        strategies.put("idCard", new IdCardDesensitizeStrategy());
        return new DesensitizeService(new DesensitizeStrategyRegistry(strategies));
    }

}
