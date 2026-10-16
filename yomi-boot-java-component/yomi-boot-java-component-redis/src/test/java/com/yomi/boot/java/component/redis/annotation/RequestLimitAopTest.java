package com.yomi.boot.java.component.redis.annotation;

import com.yomi.boot.java.component.redis.ops.extension.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @RequestLimit} 解析与代理形态无关（桩限流器只记录限流键，不碰 Redis）：
 * <ol>
 *   <li>注解写在实现类方法上：CGLIB（Boot 默认）与 JDK 代理（宿主配 {@code spring.aop.proxy-target-class=false}）都必须生效；</li>
 *   <li>SpEL 参数名取自"最具体方法"（实现类参数名），JDK 代理下键才能算对；</li>
 *   <li>边界：注解只写在接口方法上时 {@code @annotation} 切点不匹配（切面整体不生效），
 *   注解必须落在实现类方法上——用例锁定该边界，避免静默假设。</li>
 * </ol>
 */
class RequestLimitAopTest {

    /** 注解在实现类方法上 + 接口方法无注解 */
    interface ImplAnnotatedService {

        String create(String userId);
    }

    static class ImplAnnotatedServiceImpl implements ImplAnnotatedService {

        @RequestLimit(key = "#userId", replenishRate = 1, burstCapacity = 1)
        @Override
        public String create(String userId) {
            return "ok:" + userId;
        }
    }

    /** 注解只写在接口方法上 + 实现类方法无注解 */
    interface InterfaceAnnotatedService {

        @RequestLimit(key = "iface-key", replenishRate = 1, burstCapacity = 1)
        String run();
    }

    static class InterfaceAnnotatedServiceImpl implements InterfaceAnnotatedService {

        @Override
        public String run() {
            return "ran";
        }
    }

    @Test
    void jdkProxyResolvesAnnotationOnImplementationMethod() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();

        ImplAnnotatedService service =
                jdkProxy(ImplAnnotatedService.class, new ImplAnnotatedServiceImpl(), limiter);

        assertEquals("ok:u-1", service.create("u-1"), "业务方法必须正常执行");
        assertEquals(List.of("u-1"), limiter.keys, "JDK 代理下注解与 SpEL 参数都必须按实现类方法解析");
    }

    @Test
    void cglibProxyResolvesAnnotationOnImplementationMethod() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();

        ImplAnnotatedService service = cglibProxy(new ImplAnnotatedServiceImpl(), limiter);

        assertEquals("ok:u-1", service.create("u-1"));
        assertEquals(List.of("u-1"), limiter.keys);
    }

    @Test
    void interfaceDeclaredAnnotationIsNotMatchedWithJdkProxy() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();

        InterfaceAnnotatedService service =
                jdkProxy(InterfaceAnnotatedService.class, new InterfaceAnnotatedServiceImpl(), limiter);

        assertEquals("ran", service.run());
        assertEquals(List.of(), limiter.keys,
                "注解只在接口方法上时 @annotation 切点不匹配（切面不生效）——注解须写在实现类方法上");
    }

    @Test
    void interfaceDeclaredAnnotationIsNotMatchedWithCglibProxy() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();

        InterfaceAnnotatedService service = cglibProxy(new InterfaceAnnotatedServiceImpl(), limiter);

        assertEquals("ran", service.run());
        assertEquals(List.of(), limiter.keys,
                "注解只在接口方法上时 @annotation 切点不匹配（切面不生效）——注解须写在实现类方法上");
    }

    /** JDK 动态代理（宿主配 spring.aop.proxy-target-class=false 的等价形态） */
    private static <T> T jdkProxy(Class<T> serviceInterface, Object target, RedisRateLimiter limiter) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setInterfaces(serviceInterface);
        factory.setProxyTargetClass(false);
        factory.addAspect(new RequestLimitAop(limiter));
        return factory.getProxy();
    }

    /** CGLIB 代理（Boot 默认 proxy-target-class=true） */
    private static <T> T cglibProxy(Object target, RedisRateLimiter limiter) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new RequestLimitAop(limiter));
        return factory.getProxy();
    }

    /** 桩限流器：只记录收到的限流键，恒放行 */
    static class RecordingRateLimiter extends RedisRateLimiter {

        final List<String> keys = new ArrayList<>();

        RecordingRateLimiter() {
            super("test:ratelimit:", new StringRedisTemplate());
        }

        @Override
        public boolean isAllowed(String key, int replenishRate, int burstCapacity, int requestedTokens) {
            keys.add(key);
            return true;
        }

        @Override
        public <T> T acquire(String key, int replenishRate, int burstCapacity, int requestedTokens, int ttl,
                             Supplier<T> supplier) {
            keys.add(key);
            return supplier.get();
        }
    }

}
