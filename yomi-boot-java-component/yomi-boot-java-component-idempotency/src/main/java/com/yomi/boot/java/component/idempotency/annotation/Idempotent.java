package com.yomi.boot.java.component.idempotency.annotation;

import java.lang.annotation.*;

/**
 * 声明方法需要幂等保护：重复请求返回同一成功结果，或提示「处理中」。
 * <p>
 * 下列属性均支持 SpEL：
 * <ul>
 *     <li>不含 {@code #}：当作普通字符串，不做解析（如 {@code "order:pay"}）</li>
 *     <li>含 {@code #}：按 Spring SpEL 求值；方法形参以<strong>参数名</strong>为变量（需 {@code -parameters} 编译），
 *         例如 {@code #orderId}、{@code #request.userId}</li>
 * </ul>
 * 解析后的 key 不能为空；namespace 解析后仍为空时使用「类全限定名#方法名」。
 *
 * @author zwei
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等键（必填），与 namespace 一起在库表唯一。
     * 示例：{@code "#header['Idempotency-Key']"}、{@code "#bizId"}
     */
    String key();

    /**
     * 命名空间，隔离不同接口或业务；默认空。
     * 示例：{@code "order:create"} 或 SpEL {@code "#tenantId + ':order'"}
     */
    String namespace() default "";

    /**
     * 请求指纹（可选），用于「同键、不同请求体」时拒绝（错误码 IDEMPOTENCY_KEY_CONFLICT）。
     * 示例：对请求体做 hash 的 SpEL，或固定字段拼接。
     */
    String requestFingerprint() default "";

}
