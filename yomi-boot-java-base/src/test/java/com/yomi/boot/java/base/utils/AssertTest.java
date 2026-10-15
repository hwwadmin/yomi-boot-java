package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.exception.AssertException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 断言工具：触发条件与消息格式化
 */
class AssertTest {

    @Test
    void isTrue() {
        assertDoesNotThrow(() -> Assert.isTrue(true));
        AssertException e = assertThrows(AssertException.class, () -> Assert.isTrue(false));
        assertEquals("[Assertion failed]", e.getMessage());
    }

    @Test
    void isTrueWithFormattedMessage() {
        AssertException e = assertThrows(AssertException.class,
                () -> Assert.isTrue(false, "id {} 非法", -1));
        assertEquals("id -1 非法", e.getMessage());
    }

    @Test
    void isFalse() {
        assertDoesNotThrow(() -> Assert.isFalse(false));
        assertThrows(AssertException.class, () -> Assert.isFalse(true));
        assertThrows(AssertException.class, () -> Assert.isFalse(true, "必须为假"));
    }

    @Test
    void isNull() {
        assertDoesNotThrow(() -> Assert.isNull(null));
        assertThrows(AssertException.class, () -> Assert.isNull(new Object()));
        assertThrows(AssertException.class, () -> Assert.isNull("x", "必须为空"));
    }

    @Test
    void nonNull() {
        assertDoesNotThrow(() -> Assert.nonNull(new Object()));
        assertThrows(AssertException.class, () -> Assert.nonNull(null));
        AssertException e = assertThrows(AssertException.class,
                () -> Assert.nonNull(null, "参数 {} 不能为空", "name"));
        assertEquals("参数 name 不能为空", e.getMessage());
    }

}
