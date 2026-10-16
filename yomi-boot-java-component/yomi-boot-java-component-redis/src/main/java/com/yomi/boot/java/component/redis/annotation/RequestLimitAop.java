package com.yomi.boot.java.component.redis.annotation;

import com.yomi.boot.java.base.exception.AbstractRuntimeException;
import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.redis.ops.extension.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求限流注解切面实现
 * 基于{@link RedisRateLimiter}实现
 * <p>
 * 限流器默认由 {@link com.yomi.boot.java.component.redis.config.RateLimitConfig} 提供，
 * 可自定义 RedisRateLimiter Bean 覆盖；
 * 切面与限流器同以 {@code yomi.ratelimit} 属性为开关，属性不设置则整体不生效
 *
 * @author zwei
 */
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.ratelimit")
public class RequestLimitAop {

    /** 注解 key 表达式是编译期常量，按表达式缓存解析结果，避免每次请求重建 AST */
    private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

    /** SpEL 解析限流键表达式 */
    private final ExpressionParser spelExpressionParser = new SpelExpressionParser();

    /** 令牌桶限流 */
    private final RedisRateLimiter redisRateLimiter;

    @Pointcut("@annotation(com.yomi.boot.java.component.redis.annotation.RequestLimit)")
    public void pointCut() {
    }

    @Around("pointCut()")
    public Object doRedisLimit(ProceedingJoinPoint point) {
        // valid
        if (!(point.getSignature() instanceof MethodSignature signature))
            throw new IllegalArgumentException("@RequestLimit 注解只能用于方法上");

        Object[] args = point.getArgs();
        // 按“最具体方法”（实现类方法）解析注解与参数名：宿主配 spring.aop.proxy-target-class=false 时
        // signature 拿到的是接口方法，注解只写在实现类方法上时直接取会为空
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), point.getTarget().getClass());
        RequestLimit redisLimit = AnnotatedElementUtils.findMergedAnnotation(method, RequestLimit.class);
        // generate key
        String key;
        if (redisLimit.key().contains("#")) {
            EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
            for (int i = 0; i < method.getParameters().length; i++) {
                String name = method.getParameters()[i].getName();
                Object arg = args[i];
                context.setVariable(name, arg);
            }
            Expression expression = EXPRESSION_CACHE.computeIfAbsent(redisLimit.key(),
                    spelExpressionParser::parseExpression);
            key = expression.getValue(context, String.class);
        } else {
            key = redisLimit.key();
        }
        if (StringUtils.isBlank(key)) {
            // 限流键为 null 时使用固定占位，避免共用同一桶
            throw BizException.exception("限流 key 不能为空，请检查 @RequestLimit key 表达式: " + redisLimit.key());
        }
        int replenishRate = redisLimit.replenishRate();
        int burstCapacity = redisLimit.burstCapacity();
        int requestedTokens = redisLimit.requestedTokens();
        if (redisLimit.rejectStrategy() == RequestLimitRejectStrategy.REJECT) {
            if (!redisRateLimiter.isAllowed(key, replenishRate, burstCapacity, requestedTokens)) {
                throw BizException.exception("已被限流，请稍后重试");
            }
            return proceed(point);
        }
        return redisRateLimiter.acquire(key, replenishRate, burstCapacity, requestedTokens, redisLimit.ttl(),
                () -> proceed(point));
    }

    /** 执行切面目标方法并统一异常类型 */
    private static Object proceed(ProceedingJoinPoint point) {
        try {
            return point.proceed();
        } catch (AbstractRuntimeException e) {
            // 框架业务异常透传，保留原始错误码与消息
            throw e;
        } catch (Error e) {
            // 系统级 Error 原样抛出，不能包装成业务异常掩盖故障
            throw e;
        } catch (Throwable e) {
            throw BizException.exception(e, "请求限流处理失败");
        }
    }

}
