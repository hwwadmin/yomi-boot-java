package com.yomi.boot.java.component.redis.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 通用模板的类型化读取：value 序列化器以 {@code Object} 为目标且不写类型信息，
 * 直接 {@code opsForValue().get} 只能拿回 {@code LinkedHashMap}，需要 POJO 时走 {@code get(key, Class)}。
 */
class CommonRedisTemplateTest {

    @Test
    void typedGetConvertsDeserializedStructureBackToPojo() {
        // 模拟"Object 目标"序列化器的读回结果：POJO 变成 LinkedHashMap
        Map<String, Object> asMap = new LinkedHashMap<>();
        asMap.put("id", 7L);
        asMap.put("name", "张三");
        CommonRedisTemplate template = templateReturning(asMap);

        Order order = template.get("order:1", Order.class);

        assertEquals(7L, order.id());
        assertEquals("张三", order.name());
    }

    @Test
    void typedGetReturnsNullWhenKeyMissing() {
        CommonRedisTemplate template = templateReturning(null);

        assertNull(template.get("order:missing", Order.class));
    }

    /** 覆写 opsForValue().get 返回固定值的模板（不连 Redis） */
    private static CommonRedisTemplate templateReturning(Object canned) {
        return new CommonRedisTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public ValueOperations<String, Object> opsForValue() {
                return (ValueOperations<String, Object>) Proxy.newProxyInstance(
                        CommonRedisTemplateTest.class.getClassLoader(),
                        new Class<?>[]{ValueOperations.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "get" -> canned;
                            case "toString" -> "stub";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> false;
                            default -> null;
                        });
            }
        };
    }

    record Order(long id, String name) {
    }

}
