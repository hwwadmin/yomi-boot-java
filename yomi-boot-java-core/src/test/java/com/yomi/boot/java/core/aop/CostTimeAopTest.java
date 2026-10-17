package com.yomi.boot.java.core.aop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.core.annotation.CostTime;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 耗时阈值解析：注解标在实现类方法上、JDK 动态代理拿到的却是接口方法时，
 * 自定义阈值依然生效（200ms 默认值只在无注解时使用）
 */
class CostTimeAopTest {

    /** 接口方法无注解，注解只在实现类上 */
    interface Work {

        void work();
    }

    static class AnnotatedWork implements Work {

        @CostTime(50)
        @Override
        public void work() {
            sleep(60L);
        }
    }

    static class PlainWork implements Work {

        @Override
        public void work() {
            sleep(60L);
        }
    }

    /** 注解在接口方法上的形态（CGLIB 代理拿到实现类方法时同样要能找到） */
    interface AnnotatedInterface {

        @CostTime(50)
        void work();
    }

    static class PlainImpl implements AnnotatedInterface {

        @Override
        public void work() {
            sleep(60L);
        }
    }

    @Test
    void thresholdResolvedFromImplementationMethodBehindInterface() throws Exception {
        Method interfaceMethod = Work.class.getMethod("work");
        assertEquals(50L, CostTimeAop.resolveWarnThreshold(interfaceMethod, AnnotatedWork.class),
                "实现类方法上的注解必须生效，不得退回默认阈值");
        assertEquals(CostTime.DEFAULT_WARN_THRESHOLD_MS,
                CostTimeAop.resolveWarnThreshold(interfaceMethod, PlainWork.class));
    }

    @Test
    void thresholdResolvedFromInterfaceMethod() throws Exception {
        Method implMethod = PlainImpl.class.getMethod("work");
        assertEquals(50L, CostTimeAop.resolveWarnThreshold(implMethod, PlainImpl.class),
                "接口方法上的注解必须生效（合并查找方法层级）");
    }

    @Test
    void customThresholdTakesEffectThroughJdkDynamicProxy() {
        Logger logger = (Logger) LoggerFactory.getLogger(CostTimeAop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AspectJProxyFactory factory = new AspectJProxyFactory(new AnnotatedWork());
            factory.setInterfaces(Work.class);
            factory.setProxyTargetClass(false);
            factory.addAspect(new CostTimeAop());
            Work proxy = factory.getProxy();
            proxy.work();
        } finally {
            logger.detachAppender(appender);
        }
        // 耗时 60ms：命中 50ms 自定义阈值应告警（200ms 默认阈值不会触发）
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("请求超过50ms")),
                () -> "期望命中 50ms 自定义阈值，实际日志: " + appender.list);
    }

    @Test
    void defaultThresholdKeepsQuietWhenBelow() {
        Logger logger = (Logger) LoggerFactory.getLogger(CostTimeAop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AspectJProxyFactory factory = new AspectJProxyFactory(new PlainWork());
            factory.setInterfaces(Work.class);
            factory.setProxyTargetClass(false);
            factory.addAspect(new CostTimeAop());
            Work proxy = factory.getProxy();
            proxy.work();
        } finally {
            logger.detachAppender(appender);
        }
        assertFalse(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN),
                () -> "60ms 低于默认 200ms 阈值，不应告警，实际日志: " + appender.list);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
