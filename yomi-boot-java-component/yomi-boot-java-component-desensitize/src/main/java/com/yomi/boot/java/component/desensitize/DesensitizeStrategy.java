package com.yomi.boot.java.component.desensitize;

/**
 * 脱敏策略：一条脱敏规则，按名称注册到 {@link DesensitizeStrategyRegistry}
 * <p>
 * 约定：null 返回 null，空串原样返回（继承 {@link AbstractDesensitizeStrategy} 即自动满足；
 * 直接实现本接口时 {@link DesensitizeStrategyRegistry} 入口已判空，入参不会是 null/空串）。
 * 宿主自定义：实现本接口（或继承基类）并注册为 Bean，用新的策略名；与内置策略名重名会在启动期报错。
 * 策略名非空且唯一即可，风格不限（内置用 MOBILE 这类大写名）。
 *
 * @author zwei
 */
public interface DesensitizeStrategy {

    /**
     * 策略名称：注册与查找的键，非空且全局唯一（风格不限）
     */
    String name();

    /**
     * 执行脱敏；null 返回 null，空串原样返回
     */
    String mask(String value);

}
