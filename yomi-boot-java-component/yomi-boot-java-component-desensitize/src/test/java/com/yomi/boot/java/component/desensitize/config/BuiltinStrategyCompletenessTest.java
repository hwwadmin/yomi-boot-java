package com.yomi.boot.java.component.desensitize.config;

import com.yomi.boot.java.component.desensitize.DesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategyRegistry;
import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置策略名齐备性校验测试（{@link DesensitizeAutoConfiguration#requireBuiltinStrategies}）
 * <p>
 * 该类刻意不含任何 @Configuration/@Component 夹具：它所在包会被装配测试的 @ComponentScan 扫到。
 *
 * @author zwei
 */
class BuiltinStrategyCompletenessTest {

    @Test
    void missingName_failsFast() {
        DesensitizeStrategyRegistry incomplete = registry(new KeepEdgeDesensitizeStrategy("CUSTOM_ONLY", 1, 0));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DesensitizeAutoConfiguration.requireBuiltinStrategies(incomplete));
        assertTrue(ex.getMessage().contains("缺少内置脱敏策略"), ex.getMessage());
        assertTrue(ex.getMessage().contains(DesensitizeType.MOBILE.name()), ex.getMessage());
        assertTrue(ex.getMessage().contains("CUSTOM_ONLY"), ex.getMessage());
    }

    @Test
    void allNamesPresent_passes() {
        Map<String, DesensitizeStrategy> strategies = new LinkedHashMap<>();
        for (DesensitizeType type : DesensitizeType.values()) {
            strategies.put(type.name().toLowerCase() + "Strategy",
                    new KeepEdgeDesensitizeStrategy(type.name(), 1, 0));
        }
        assertDoesNotThrow(() -> DesensitizeAutoConfiguration.requireBuiltinStrategies(
                new DesensitizeStrategyRegistry(strategies)));
    }

    private static DesensitizeStrategyRegistry registry(DesensitizeStrategy strategy) {
        return new DesensitizeStrategyRegistry(Map.of("customOnlyStrategy", strategy));
    }

}
