package com.yomi.boot.java.component.desensitize.service;

import com.yomi.boot.java.component.desensitize.DesensitizeStrategyRegistry;
import com.yomi.boot.java.component.desensitize.IDesensitizeService;

/**
 * 脱敏服务默认实现：委托策略中心 {@link DesensitizeStrategyRegistry}
 *
 * @author zwei
 */
public class DesensitizeService implements IDesensitizeService {

    /** 脱敏策略注册表 */
    private final DesensitizeStrategyRegistry registry;

    public DesensitizeService(DesensitizeStrategyRegistry registry) {
        this.registry = registry;
    }

    /** 按类型选择策略并脱敏 */
    @Override
    public String mask(String type, String value) {
        return registry.mask(type, value);
    }

}
