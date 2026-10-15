package com.yomi.boot.java.component.idempotency.annotation;

import com.yomi.boot.java.base.exception.AbstractRuntimeException;
import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.idempotency.core.IdempotencyGuard;
import com.yomi.boot.java.component.idempotency.core.IdempotencyKey;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;

/**
 * 拦截带 Idempotent 的方法：解析幂等键后交给 IdempotencyGuard 执行
 *
 * @author zwei
 */
@Aspect
@RequiredArgsConstructor
public class IdempotentAop {

    /** 幂等守卫 */
    private final IdempotencyGuard guard;

    /** 匹配标注了 {@link Idempotent} 的方法 */
    @Pointcut("@annotation(com.yomi.boot.java.component.idempotency.annotation.Idempotent)")
    public void pointCut() {
    }

    /** 解析幂等键与命名空间后委托 {@link IdempotencyGuard} 执行环绕逻辑 */
    @Around("pointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        if (!(point.getSignature() instanceof MethodSignature signature)) {
            throw new IllegalArgumentException("@Idempotent 注解只能用于方法上");
        }
        Method method = signature.getMethod();
        Idempotent idempotent = AnnotationUtils.findAnnotation(method, Idempotent.class);
        if (idempotent == null) {
            throw new IllegalStateException("@Idempotent 未解析到注解: " + method);
        }
        Object[] args = point.getArgs();

        String idempotencyKey = IdempotentSpelSupport.resolve(idempotent.key(), method, args);
        if (StringUtils.isBlank(idempotencyKey)) {
            throw BizException.exception("幂等 key 不能为空，请检查 @Idempotent key 表达式: " + idempotent.key());
        }

        String namespace = IdempotentSpelSupport.resolve(idempotent.namespace(), method, args);
        if (StringUtils.isBlank(namespace)) {
            namespace = point.getTarget().getClass().getName() + "#" + method.getName();
        }

        String requestHash = IdempotentSpelSupport.resolve(idempotent.requestFingerprint(), method, args);
        IdempotencyKey key = new IdempotencyKey(namespace, idempotencyKey, StringUtils.trimToNull(requestHash));

        try {
            return guard.executeAround(point, key);
        } catch (AbstractRuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw BizException.exception(e, "幂等处理失败");
        }
    }

}
