package com.yomi.boot.java.component.idempotency.annotation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析 Idempotent 注解属性：无 {@code #} 原样返回，有 {@code #} 则按形参名绑定后 SpEL 求值
 *
 * @author zwei
 */
final class IdempotentSpelSupport {

    /** 表达式字符串 → 已解析 AST（缓存后热路径直接复用） */
    private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();
    /** SpEL 表达式解析器 */
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private IdempotentSpelSupport() {
    }

    /**
     * 不含 {@code #} 时原样返回；含 {@code #} 时把各形参按参数名放入上下文后求值为 String
     */
    static String resolve(String expression, Method method, Object[] args) {
        if (StringUtils.isBlank(expression)) {
            return expression;
        }
        if (!expression.contains("#")) {
            return expression;
        }
        EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
        var parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            context.setVariable(parameters[i].getName(), args[i]);
        }
        Expression parsed = EXPRESSION_CACHE.computeIfAbsent(expression, PARSER::parseExpression);
        return parsed.getValue(context, String.class);
    }

}
