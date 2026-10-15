package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link KeepEdgeDesensitizeStrategy} 单元测试：宿主自定义保留位数（工号、学号等）的复用点
 *
 * @author zwei
 */
class KeepEdgeDesensitizeStrategyTest {

    @Test
    void mask_customKeep() {
        assertEquals("ABCD****IJ", new KeepEdgeDesensitizeStrategy("STUDENT_NO", 4, 2).mask("ABCDEFGHIJ"));
        assertEquals("******", new KeepEdgeDesensitizeStrategy("STUDENT_NO", 4, 6).mask("ABCDEF"));
        // 保留位数之和溢出 int 时仍按"长度不足整体遮蔽"处理
        assertEquals("***", new KeepEdgeDesensitizeStrategy("STUDENT_NO", Integer.MAX_VALUE, 1).mask("abc"));
    }

    @Test
    void constructor_negativeKeep_failFast() {
        assertThrows(IllegalArgumentException.class, () -> new KeepEdgeDesensitizeStrategy("STUDENT_NO", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new KeepEdgeDesensitizeStrategy("STUDENT_NO", 1, -1));
    }

    @Test
    void mask_nullOrEmpty_returnAsIs() {
        KeepEdgeDesensitizeStrategy strategy = new KeepEdgeDesensitizeStrategy("STUDENT_NO", 4, 2);
        assertNull(strategy.mask(null));
        assertEquals("", strategy.mask(""));
    }

}
