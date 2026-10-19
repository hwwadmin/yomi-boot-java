package com.yomi.boot.java.component.idempotency.testsupport;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 测试用的两种"调用点事务边界"：
 * <ul>
 *   <li>{@link #inTransactional}：真实声明式事务切面（{@code TransactionInterceptor} + 代理）——
 *       幂等"沿用调用点事务"的形态，守卫能拿到事务状态（保存点靠它）</li>
 *   <li>{@link #inProgrammatic}：调用方自己用 {@code TransactionTemplate} 开的事务——
 *       事务活跃但拿不到事务状态，守卫应报错要求显式传模板</li>
 * </ul>
 */
public final class Tx {

    private Tx() {
    }

    /** 在声明式事务切面里跑一段逻辑（模拟业务用例入口上的 @Transactional） */
    @SuppressWarnings("unchecked")
    public static <T> T inTransactional(PlatformTransactionManager txManager, Supplier<T> body) {
        NameMatchTransactionAttributeSource source = new NameMatchTransactionAttributeSource();
        RuleBasedTransactionAttribute required = new RuleBasedTransactionAttribute();
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        source.addTransactionalMethod("run", required);

        ProxyFactory factory = new ProxyFactory(new Target<>(body));
        factory.addAdvice(new TransactionInterceptor(txManager, source));
        return (T) ((Target<T>) factory.getProxy()).run();
    }

    /** 在调用方自己的模板里跑（外层事务不是声明式事务托管） */
    public static <T> T inProgrammatic(PlatformTransactionManager txManager, Supplier<T> body) {
        return new TransactionTemplate(txManager).execute(status -> body.get());
    }

    /** 被事务切面代理的目标：{@code run} 就是"用例入口" */
    public static class Target<T> {

        private final Supplier<T> body;

        public Target(Supplier<T> body) {
            this.body = body;
        }

        public T run() {
            return body.get();
        }
    }

}
