package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.IDesensitizeService;
import com.yomi.boot.java.component.desensitize.DesensitizeStrategyRegistry;
import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.config.DesensitizeAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 内置策略规则测试：策略取自真实装配的容器（顺带校验注册集合与 {@link DesensitizeType} 一致），
 * 输出与各策略类 javadoc 示例逐条对齐
 *
 * @author zwei
 */
class BuiltinDesensitizeStrategyRulesTest {

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DesensitizeAutoConfiguration.class));

    @Test
    void registeredNames_matchDesensitizeType() {
        // 集合相等：内置策略由 @Component 扫描注册，注册顺序不保证与枚举一致
        Set<String> expected = Set.copyOf(Arrays.stream(DesensitizeType.values()).map(Enum::name).toList());
        RUNNER.run(context -> assertEquals(expected, context.getBean(DesensitizeStrategyRegistry.class).names()));
    }

    @Test
    void mask_keepEdgeRules() {
        withService(service -> {
            assertEquals("138****5678", mask(service, DesensitizeType.MOBILE, "13812345678"));
            assertEquals("0571*******88", mask(service, DesensitizeType.FIXED_PHONE, "0571-88888888"));
            assertEquals("110101********4567", mask(service, DesensitizeType.ID_CARD, "110101199003074567"));
            assertEquals("6222***********3445", mask(service, DesensitizeType.BANK_CARD, "6222020200112233445"));
            assertEquals("浙江省杭州市******", mask(service, DesensitizeType.ADDRESS, "浙江省杭州市西湖区文三路"));
            assertEquals("浙A***45", mask(service, DesensitizeType.CAR_NO, "浙A12345"));
            assertEquals("E1*****78", mask(service, DesensitizeType.PASSPORT, "E12345678"));
            assertEquals("z******n", mask(service, DesensitizeType.USERNAME, "zhangsan"));
            assertEquals("***", mask(service, DesensitizeType.ALL, "abc"));
        });
    }

    @Test
    void mask_shortValue_masksEntirely() {
        withService(service -> {
            // 长度不足以保留前后位数时整体遮蔽，避免泄露
            assertEquals("*******", mask(service, DesensitizeType.MOBILE, "1381234"));
            assertEquals("**", mask(service, DesensitizeType.MOBILE, "ab"));
            assertEquals("******", mask(service, DesensitizeType.BANK_CARD, "622202"));
        });
    }

    @Test
    void mask_nameRule() {
        withService(service -> {
            assertEquals("*", mask(service, DesensitizeType.NAME, "张"));
            assertEquals("张*", mask(service, DesensitizeType.NAME, "张三"));
            assertEquals("张*丰", mask(service, DesensitizeType.NAME, "张三丰"));
            assertEquals("欧**娜", mask(service, DesensitizeType.NAME, "欧阳娜娜"));
        });
    }

    @Test
    void mask_emailRule() {
        withService(service -> {
            assertEquals("a*****@qq.com", mask(service, DesensitizeType.EMAIL, "abcdef@qq.com"));
            assertEquals("a*****", mask(service, DesensitizeType.EMAIL, "abcdef"));
            assertEquals("@******", mask(service, DesensitizeType.EMAIL, "@qq.com"));
        });
    }

    @Test
    void mask_passwordRule_ignoresLength() {
        withService(service -> {
            assertEquals("******", mask(service, DesensitizeType.PASSWORD, "abc"));
            assertEquals("******", mask(service, DesensitizeType.PASSWORD, "0123456789"));
        });
    }

    @Test
    void mask_nullOrEmpty_returnAsIs() {
        withService(service -> {
            assertNull(service.mask(DesensitizeType.MOBILE.name(), null));
            assertEquals("", service.mask(DesensitizeType.MOBILE.name(), ""));
        });
    }

    @Test
    void mask_surrogatePairNotSplit() {
        withService(service -> {
            // 𠮷（U+20BB7）为代理对，按 code point 计数与切分，不应被截断
            String name = "\uD842\uDFB7野家";
            assertEquals("\uD842\uDFB7*家", mask(service, DesensitizeType.NAME, name));
            assertEquals("***", mask(service, DesensitizeType.ALL, name));
        });
    }

    @Test
    void mask_isIdempotent() {
        // 已脱敏值再次脱敏保持不变（日志/出参二次处理场景）
        Map<DesensitizeType, String> samples = Map.ofEntries(
                Map.entry(DesensitizeType.ALL, "abc"),
                Map.entry(DesensitizeType.NAME, "张三丰"),
                Map.entry(DesensitizeType.MOBILE, "13812345678"),
                Map.entry(DesensitizeType.FIXED_PHONE, "0571-88888888"),
                Map.entry(DesensitizeType.ID_CARD, "110101199003074567"),
                Map.entry(DesensitizeType.BANK_CARD, "6222020200112233445"),
                Map.entry(DesensitizeType.EMAIL, "abcdef@qq.com"),
                Map.entry(DesensitizeType.ADDRESS, "浙江省杭州市西湖区文三路"),
                Map.entry(DesensitizeType.CAR_NO, "浙A12345"),
                Map.entry(DesensitizeType.PASSPORT, "E12345678"),
                Map.entry(DesensitizeType.USERNAME, "zhangsan"),
                Map.entry(DesensitizeType.PASSWORD, "pass123"));
        withService(service -> samples.forEach((type, value) -> {
            String once = mask(service, type, value);
            assertEquals(once, mask(service, type, once), type.name());
        }));
    }

    private static String mask(IDesensitizeService service, DesensitizeType type, String value) {
        return service.mask(type.name(), value);
    }

    private static void withService(Consumer<IDesensitizeService> assertions) {
        RUNNER.run(context -> assertions.accept(context.getBean(IDesensitizeService.class)));
    }

}
