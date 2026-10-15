package com.yomi.boot.java.component.ds.support;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页链自检的四种形态：正常在位放行；缺分页**一律 fail-fast**——MP 默认链抢占、
 * 宿主自管链缺分页、一个链都没有，三种都拦启动（分页是硬依赖：按 Page 取批的语句靠它加 LIMIT）。
 */
class MybatisPlusChainCheckerTest {

    @Test
    void passesWhenPaginationInPlace() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                "mybatisPlusInterceptor", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor()))));

        assertDoesNotThrow(checker::afterSingletonsInstantiated);
    }

    @Test
    void failsFastWhenMpDefaultChainStoleTheBean() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                MybatisPlusChainChecker.MP_DEFAULT_BEAN_NAME, chain(new BlockAttackInnerInterceptor()))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains(MybatisPlusChainChecker.MP_DEFAULT_BEAN_NAME), ex.getMessage());
        assertTrue(ex.getMessage().contains("定义自己的 MybatisPlusInterceptor Bean"), "要给出一条出路: " + ex.getMessage());
    }

    @Test
    void failsFastWhenHostManagedChainMissesPagination() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                "hostChain", chain(new BlockAttackInnerInterceptor()))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("PaginationInnerInterceptor"), "要给出一条出路: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("按 Page 取批的语句"), "要说清为什么必须补: " + ex.getMessage());
    }

    /** 宿主自管链但补上了分页：放行 */
    @Test
    void passesWhenHostManagedChainHasPagination() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of(
                "hostChain", chain(new PaginationInnerInterceptor(), new BlockAttackInnerInterceptor()))));

        assertDoesNotThrow(checker::afterSingletonsInstantiated);
    }

    @Test
    void failsFastWhenNoInterceptorBeanAtAll() {
        MybatisPlusChainChecker checker = new MybatisPlusChainChecker(context(Map.of()));

        IllegalStateException ex = assertThrows(IllegalStateException.class, checker::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("mybatis-plus-spring-boot4-starter"), ex.getMessage());
    }

    private static MybatisPlusInterceptor chain(InnerInterceptor... inners) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        for (InnerInterceptor inner : inners) {
            interceptor.addInnerInterceptor(inner);
        }
        return interceptor;
    }

    /** 只回答 getBeansOfType 的上下文桩（自检只用到这一个能力） */
    private static ApplicationContext context(Map<String, MybatisPlusInterceptor> beans) {
        return (ApplicationContext) Proxy.newProxyInstance(MybatisPlusChainCheckerTest.class.getClassLoader(),
                new Class<?>[]{ApplicationContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBeansOfType" -> beans;
                    case "toString" -> "stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> false;
                    default -> null;
                });
    }

}
