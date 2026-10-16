package com.yomi.boot.java.component.desensitize;

/**
 * 脱敏服务（门面）
 * <p>
 * 业务代码注入本接口使用即可，策略名用内置的 {@link DesensitizeType}（12 项）或宿主自定义策略名称。
 * 默认实现 {@link com.yomi.boot.java.component.desensitize.service.DesensitizeService} 由
 * {@link com.yomi.boot.java.component.desensitize.config.DesensitizeAutoConfiguration} 装配，
 * 宿主自行实现本接口并注册为 Bean 后，默认实现不再装配。
 *
 * @author zwei
 */
public interface IDesensitizeService {

    /**
     * 按策略名称脱敏：null 返回 null，空串原样返回
     *
     * @param type  策略名称，内置见 {@link DesensitizeType}，自定义见 {@link DesensitizeStrategy}
     * @param value 待脱敏的值
     * @throws IllegalArgumentException 策略名称未注册
     */
    String mask(String type, String value);

}
