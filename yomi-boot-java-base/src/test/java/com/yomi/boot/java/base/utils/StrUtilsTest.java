package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.AssertException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 字符串工具：连接、SLF4J风格占位、驼峰/下划线互转
 */
class StrUtilsTest {

    @Test
    void joinWithDefaultComma() {
        assertEquals("", StrUtils.join(null));
        assertEquals("", StrUtils.join(List.of()));
        assertEquals("a,b,c", StrUtils.join(List.of("a", "b", "c")));
    }

    @Test
    void joinWithCustomDelimiter() {
        assertEquals("a-b", StrUtils.join(List.of("a", "b"), "-"));
    }

    @Test
    void joinRejectsNullDelimiterWithAssert() {
        // null 分隔符抛 AssertException
        assertThrows(AssertException.class, () -> StrUtils.join(List.of("a", "b"), null));
    }

    @Test
    void formatSlf4jPlaceholders() {
        assertEquals("a=1 b=x", StrUtils.format("a={} b={}", 1, "x"));
        assertEquals("no params", StrUtils.format("no params"));
        assertEquals("no params", StrUtils.format("no params", (Object[]) null));
        assertEquals("null", StrUtils.format(null));
        // 未消费的占位符保持原样，多余的参数以 {,} 附加（与 SLF4J 一致）
        assertEquals("a=1", StrUtils.format("a={}", 1, 2));
    }

    @Test
    void toCamelCase() {
        assertNull(StrUtils.toCamelCase(null));
        assertEquals("already", StrUtils.toCamelCase("already"));
        assertEquals("userName", StrUtils.toCamelCase("user_name"));
        assertEquals("userNameId", StrUtils.toCamelCase("user_name_id"));
        assertEquals("aB", StrUtils.toCamelCase("A_B"));
        assertEquals("leading", StrUtils.toCamelCase("_leading"));
    }

    @Test
    void toUnderlineCase() {
        assertNull(StrUtils.toUnderlineCase(null));
        assertEquals("user_name", StrUtils.toUnderlineCase("userName"));
        assertEquals("user_name", StrUtils.toUnderlineCase("UserName"));
        assertEquals("user_id", StrUtils.toUnderlineCase("userId"));
    }

    @Test
    void upperFirst() {
        assertNull(StrUtils.upperFirst(null));
        assertEquals("", StrUtils.upperFirst(""));
        assertEquals("Name", StrUtils.upperFirst("name"));
        assertEquals("Name", StrUtils.upperFirst("Name"));
    }

}
