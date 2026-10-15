package com.yomi.boot.java.core.aop;

import com.yomi.boot.java.core.annotation.CostTime;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 耗时计算注解支持类
 *
 * @author zwei
 */
@Slf4j
@Aspect
@Component
public class CostTimeAop {

    @Pointcut("@annotation(com.yomi.boot.java.core.annotation.CostTime)")
    public void pointCut() {
    }

    @Around("pointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long beginTime = System.currentTimeMillis();
        try {
            // 执行方法
            return point.proceed();
        } finally {
            long time = System.currentTimeMillis() - beginTime;
            logCostTime(point, time);
        }
    }

    /** 按阈值输出耗时 WARN，否则 DEBUG */
    private void logCostTime(ProceedingJoinPoint joinPoint, long time) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        long warnThresholdMs = resolveWarnThreshold(signature.getMethod(), joinPoint.getTarget().getClass());
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = signature.getName();
        if (time > warnThresholdMs) {
            log.warn("请求超过{}ms class:{} method:{} cost:{}ms", warnThresholdMs, className, methodName, time);
        } else if (log.isDebugEnabled()) {
            log.debug("class:{} method:{} cost:{}ms", className, methodName, time);
        }
    }

    /**
     * 解析耗时告警阈值：JDK 动态代理下拿到的可能是接口方法，须按目标类的最具体方法做注解合并查找
     */
    static long resolveWarnThreshold(Method method, Class<?> targetClass) {
        if (Objects.isNull(method)) return CostTime.DEFAULT_WARN_THRESHOLD_MS;
        Method specificMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        CostTime costTime = AnnotatedElementUtils.findMergedAnnotation(specificMethod, CostTime.class);
        if (Objects.isNull(costTime)) return CostTime.DEFAULT_WARN_THRESHOLD_MS;
        return costTime.value() > 0 ? costTime.value() : CostTime.DEFAULT_WARN_THRESHOLD_MS;
    }

}
