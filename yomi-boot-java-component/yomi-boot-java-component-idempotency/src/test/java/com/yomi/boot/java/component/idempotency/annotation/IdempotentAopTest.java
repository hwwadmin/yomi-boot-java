package com.yomi.boot.java.component.idempotency.annotation;

import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @Idempotent} 解析与代理形态无关（桩守卫只记录解析出的幂等键，不碰 Redis/DB）：
 * <ol>
 *   <li>注解写在实现类方法上：CGLIB（Boot 默认）与 JDK 代理（宿主配 {@code spring.aop.proxy-target-class=false}）都必须生效；</li>
 *   <li>SpEL 参数名与 namespace 缺省回退按"最具体方法"/目标类取，JDK 代理下键才算得对；</li>
 *   <li>边界：注解只写在接口方法上时 {@code @annotation} 切点不匹配（切面整体不生效），
 *   注解必须落在实现类方法上——用例锁定该边界，避免静默假设。</li>
 * </ol>
 */
class IdempotentAopTest {

    /** 注解在实现类方法上 + 接口方法无注解 */
    interface ImplAnnotatedService {

        String pay(String orderId);
    }

    static class ImplAnnotatedServiceImpl implements ImplAnnotatedService {

        @Idempotent(key = "#orderId")
        @Override
        public String pay(String orderId) {
            return "paid:" + orderId;
        }
    }

    /** 注解只写在接口方法上 + 实现类方法无注解 */
    interface InterfaceAnnotatedService {

        @Idempotent(key = "iface-key")
        String run();
    }

    static class InterfaceAnnotatedServiceImpl implements InterfaceAnnotatedService {

        @Override
        public String run() {
            return "ran";
        }
    }

    @Test
    void jdkProxyResolvesAnnotationAndSpelNamesOnImplementationMethod() {
        RecordingGuard guard = new RecordingGuard();

        ImplAnnotatedService service =
                jdkProxy(ImplAnnotatedService.class, new ImplAnnotatedServiceImpl(), guard);

        assertEquals("paid:order-1", service.pay("order-1"), "业务方法必须正常执行");
        assertEquals(1, guard.keys.size(), "JDK 代理下也必须解析到实现类方法上的 @Idempotent");
        IdempotencyKey key = guard.keys.getFirst();
        assertEquals("order-1", key.idempotencyKey(), "SpEL 参数名须按实现类方法解析");
        assertEquals(ImplAnnotatedServiceImpl.class.getName() + "#pay", key.namespace(),
                "namespace 缺省回退用目标类全限定名#方法名");
    }

    @Test
    void cglibProxyResolvesAnnotationOnImplementationMethod() {
        RecordingGuard guard = new RecordingGuard();

        ImplAnnotatedService service = cglibProxy(new ImplAnnotatedServiceImpl(), guard);

        assertEquals("paid:order-1", service.pay("order-1"));
        assertEquals(1, guard.keys.size());
        assertEquals("order-1", guard.keys.getFirst().idempotencyKey());
    }

    @Test
    void interfaceDeclaredAnnotationIsNotMatchedWithJdkProxy() {
        RecordingGuard guard = new RecordingGuard();

        InterfaceAnnotatedService service =
                jdkProxy(InterfaceAnnotatedService.class, new InterfaceAnnotatedServiceImpl(), guard);

        assertEquals("ran", service.run());
        assertEquals(List.of(), guard.keys,
                "注解只在接口方法上时 @annotation 切点不匹配（切面不生效）——注解须写在实现类方法上");
    }

    @Test
    void interfaceDeclaredAnnotationIsNotMatchedWithCglibProxy() {
        RecordingGuard guard = new RecordingGuard();

        InterfaceAnnotatedService service = cglibProxy(new InterfaceAnnotatedServiceImpl(), guard);

        assertEquals("ran", service.run());
        assertEquals(List.of(), guard.keys,
                "注解只在接口方法上时 @annotation 切点不匹配（切面不生效）——注解须写在实现类方法上");
    }

    /** JDK 动态代理（宿主配 spring.aop.proxy-target-class=false 的等价形态） */
    private static <T> T jdkProxy(Class<T> serviceInterface, Object target, IdempotencyGuard guard) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setInterfaces(serviceInterface);
        factory.setProxyTargetClass(false);
        factory.addAspect(new IdempotentAop(guard));
        return factory.getProxy();
    }

    /** CGLIB 代理（Boot 默认 proxy-target-class=true） */
    private static <T> T cglibProxy(Object target, IdempotencyGuard guard) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new IdempotentAop(guard));
        return factory.getProxy();
    }

    /** 桩守卫：只记录解析出的幂等键，业务原样执行 */
    static class RecordingGuard extends IdempotencyGuard {

        final List<IdempotencyKey> keys = new ArrayList<>();

        RecordingGuard() {
            super(null, null, null, null, null);
        }

        @Override
        public Object executeAround(ProceedingJoinPoint point, IdempotencyKey key) throws Throwable {
            keys.add(key);
            return point.proceed();
        }
    }

}
