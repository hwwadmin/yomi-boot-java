package com.yomi.boot.java.component.desensitize;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DesensitizeStrategyRegistry} 单元测试：按 Bean 实例集合注册、查找、重名与报错语义
 *
 * @author zwei
 */
class DesensitizeStrategyRegistryTest {

    @Test
    void mask_registeredStrategy() {
        DesensitizeStrategyRegistry registry = registry(Map.of("mobileDesensitizeStrategy",
                new KeepEdgeDesensitizeStrategy(DesensitizeType.MOBILE.name(), 3, 4)));
        assertEquals("138****5678", registry.mask(DesensitizeType.MOBILE.name(), "13812345678"));
        assertEquals(1, registry.names().size());
    }

    @Test
    void mask_customStrategyWithNewName() {
        DesensitizeStrategyRegistry registry = registry(Map.of("studentNoDesensitizeStrategy",
                new KeepEdgeDesensitizeStrategy("STUDENT_NO", 4, 2)));
        assertEquals("2024*01", registry.mask("STUDENT_NO", "2024001"));
    }

    @Test
    void constructor_blankOrNullStrategyName_failFast() {
        // 空名/null 注册后也取不到：直接拒绝
        for (String name : new String[]{"", "  ", "\t"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> registry(Map.of("weirdStrategy", fixedName(name))), "name=" + name);
            assertTrue(ex.getMessage().contains("脱敏策略名不能为空"), ex.getMessage());
            assertTrue(ex.getMessage().contains("weirdStrategy"), ex.getMessage());
        }
        IllegalArgumentException nullName = assertThrows(IllegalArgumentException.class,
                () -> registry(Map.of("weirdStrategy", fixedName(null))));
        assertTrue(nullName.getMessage().contains("脱敏策略名不能为空"), nullName.getMessage());
    }

    @Test
    void mask_freeStyleNames_allowed() {
        // 策略名风格不限：小写、中文、含连字符等，只要非空且唯一即可用
        Map<String, DesensitizeStrategy> beans = new LinkedHashMap<>();
        beans.put("lowerCamelStrategy", new KeepEdgeDesensitizeStrategy("studentNo", 4, 2));
        beans.put("chineseStrategy", new KeepEdgeDesensitizeStrategy("学号", 4, 2));
        beans.put("hyphenStrategy", new KeepEdgeDesensitizeStrategy("MOBILE-2", 4, 2));
        DesensitizeStrategyRegistry registry = registry(beans);
        assertEquals("2024*01", registry.mask("studentNo", "2024001"));
        assertEquals("2024*01", registry.mask("学号", "2024001"));
        assertEquals("2024*01", registry.mask("MOBILE-2", "2024001"));
    }

    @Test
    void constructor_duplicateStrategyNames_failFast() {
        Map<String, DesensitizeStrategy> beans = new LinkedHashMap<>();
        beans.put("mobileDesensitizeStrategy", new KeepEdgeDesensitizeStrategy(DesensitizeType.MOBILE.name(), 3, 4));
        beans.put("myMobileStrategy", new KeepEdgeDesensitizeStrategy(DesensitizeType.MOBILE.name(), 3, 2));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> registry(beans));
        // 报错点名策略名与两个 Bean，便于定位
        assertTrue(ex.getMessage().contains(DesensitizeType.MOBILE.name()), ex.getMessage());
        assertTrue(ex.getMessage().contains("mobileDesensitizeStrategy"), ex.getMessage());
        assertTrue(ex.getMessage().contains("myMobileStrategy"), ex.getMessage());
    }

    @Test
    void mask_unknownOrBlankName_failFast() {
        DesensitizeStrategyRegistry registry = registry(Map.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> registry.mask("NOPE", "x"));
        assertTrue(ex.getMessage().contains("NOPE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("已注册"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> registry.mask(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> registry.mask(" ", "x"));
    }

    @Test
    void mask_nullOrEmptyValue_returnAsIs() {
        DesensitizeStrategyRegistry registry = registry(Map.of("mobileDesensitizeStrategy",
                new KeepEdgeDesensitizeStrategy(DesensitizeType.MOBILE.name(), 3, 4)));
        assertNull(registry.mask(DesensitizeType.MOBILE.name(), null));
        assertEquals("", registry.mask(DesensitizeType.MOBILE.name(), ""));
    }

    @Test
    void mask_nullOrEmptyValue_notDelegatedToStrategy() {
        // 中心入口兜底：宿主策略只实现接口、自身不判空时报错也不该被触发
        DesensitizeStrategy strict = new DesensitizeStrategy() {
            @Override
            public String name() {
                return "STRICT";
            }

            @Override
            public String mask(String value) {
                return value.charAt(0) + "***";
            }
        };
        DesensitizeStrategyRegistry registry = registry(Map.of("strictStrategy", strict));
        assertNull(registry.mask("STRICT", null));
        assertEquals("", registry.mask("STRICT", ""));
        assertEquals("1***", registry.mask("STRICT", "13812345678"));
        // 名称校验仍优先于值兜底：未注册名不因值为 null 而放行
        assertThrows(IllegalArgumentException.class, () -> registry.mask("NOPE", null));
    }

    @Test
    void names_isReadOnlyAndKeepsRegistrationOrder() {
        Map<String, DesensitizeStrategy> beans = new LinkedHashMap<>();
        beans.put("first", new KeepEdgeDesensitizeStrategy("FIRST", 1, 0));
        beans.put("second", new KeepEdgeDesensitizeStrategy("SECOND", 1, 0));
        DesensitizeStrategyRegistry registry = registry(beans);
        assertEquals(List.of("FIRST", "SECOND"), List.copyOf(registry.names()));
        assertThrows(UnsupportedOperationException.class, () -> registry.names().add("X"));
    }

    private static DesensitizeStrategyRegistry registry(Map<String, DesensitizeStrategy> strategyBeans) {
        return new DesensitizeStrategyRegistry(strategyBeans);
    }

    /** 测试用策略：直接实现接口返回指定名称（可为 null/空白，用于校验非法名） */
    private static DesensitizeStrategy fixedName(String name) {
        return new DesensitizeStrategy() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String mask(String value) {
                return "X";
            }
        };
    }

}
