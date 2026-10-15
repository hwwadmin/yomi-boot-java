package com.yomi.boot.java.component.desensitize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 脱敏策略中心：按名称索引策略并执行脱敏
 * <p>
 * 构造入参是容器内全部策略实例（Bean 名称 -> 策略，Bean 名称仅用于报错定位），与具体策略实现解耦：
 * 内置策略与宿主策略都是 Bean，宿主新增策略只需声明 Bean 即被收集。
 * 策略名必须**非空且全局唯一**（风格不限，自定义名称随意）：null/空白名与重名都是配置错误，
 * 构造期（启动期）直接报错；构造完成后只读。名称未注册时抛 {@link IllegalArgumentException}，不静默返回原值。
 *
 * @author zwei
 */
public class DesensitizeStrategyRegistry {

    /** 策略名称 -> 策略 */
    private final Map<String, DesensitizeStrategy> strategies;

    public DesensitizeStrategyRegistry(Map<String, DesensitizeStrategy> strategyBeans) {
        Map<String, DesensitizeStrategy> named = new LinkedHashMap<>();
        Map<String, String> beanNames = new LinkedHashMap<>();
        strategyBeans.forEach((beanName, strategy) -> {
            String name = strategy.name();
            requireValidName(name, beanName);
            String previousBean = beanNames.putIfAbsent(name, beanName);
            if (previousBean != null) {
                throw new IllegalArgumentException("脱敏策略名称重复: " + name
                        + "（Bean " + previousBean + " / " + beanName + "）");
            }
            named.put(name, strategy);
        });
        this.strategies = Collections.unmodifiableMap(named);
    }

    /**
     * 按策略名称脱敏
     * <p>
     * null/空串原样返回，不进入策略：宿主策略只实现接口、自身不判空时也不会 NPE。
     *
     * @throws IllegalArgumentException 策略名称未注册
     */
    public String mask(String type, String value) {
        DesensitizeStrategy strategy = strategy(type);
        return (value == null || value.isEmpty()) ? value : strategy.mask(value);
    }

    /**
     * 已注册的策略名称，注册顺序
     */
    public Set<String> names() {
        return strategies.keySet();
    }

    /** 策略名必须非空：null/空白名注册后也取不到 */
    private static void requireValidName(String name, String beanName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("脱敏策略名不能为空: " + name + "（Bean " + beanName + "）");
        }
    }

    /** 按名称取已注册策略，未知类型抛 {@link IllegalArgumentException} */
    private DesensitizeStrategy strategy(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("脱敏策略名称不能为空");
        }
        DesensitizeStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("未注册的脱敏策略: " + type + "，已注册: " + names());
        }
        return strategy;
    }

}
