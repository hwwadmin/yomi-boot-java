package com.yomi.boot.java.core.annotation;

import java.lang.annotation.*;

/**
 * 耗时计算注解
 *
 * @author zwei
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CostTime {

    /** 默认告警阈值（毫秒） */
    long DEFAULT_WARN_THRESHOLD_MS = 200L;

    /**
     * 耗时告警阈值（毫秒）
     */
    long value() default DEFAULT_WARN_THRESHOLD_MS;

}
