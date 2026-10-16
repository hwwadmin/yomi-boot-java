package com.yomi.boot.java.component.roundrobin.spring;

import com.yomi.boot.java.component.roundrobin.RoundRobin;
import com.yomi.boot.java.component.roundrobin.annotation.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.config.RoundRobinConfigResolver;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

/**
 * 扫描 {@link RoundRobinTask} 并注册到 {@link RoundRobinRegistry}
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class RoundRobinTaskBeanPostProcessor implements BeanPostProcessor {

    /** 轮询任务注册表 */
    private final RoundRobinRegistry registry;

    /** 扫描 @RoundRobin 方法并注册任务定义 */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        ReflectionUtils.doWithMethods(targetClass, method -> registerMethod(bean, method),
                method -> AnnotationUtils.findAnnotation(method, RoundRobinTask.class) != null);
        return bean;
    }

    /** 扫描并注册 @RoundRobinTask 方法 */
    private void registerMethod(Object bean, Method method) {
        RoundRobinTask annotation = AnnotationUtils.findAnnotation(method, RoundRobinTask.class);
        if (annotation == null) {
            return;
        }
        RoundRobin.requireValidTaskMethod(method);
        RoundRobinConfig config = RoundRobinConfigResolver.resolve(annotation);
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        registry.register(RoundRobin.task(config, RoundRobin.handler(bean, invocable)));
    }

}
