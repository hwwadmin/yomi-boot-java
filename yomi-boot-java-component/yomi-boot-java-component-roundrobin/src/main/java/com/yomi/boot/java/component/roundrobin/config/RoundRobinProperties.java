package com.yomi.boot.java.component.roundrobin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分布式轮询调度全局配置，yml 前缀 {@code yomi.roundrobin}
 * <p>
 * 任务级参数见 {@link com.yomi.boot.java.component.roundrobin.annotation.RoundRobinTask}。
 * {@code enabled=true} 时可无任务，仅装配组件。
 * <p>
 * 任务命名约定：{@code <模块><用途驼峰>}，如 {@code eventOutboxFetch}；不带服务名后缀——
 * 应用隔离由 {@code store-prefix} 保证（未配置时含 {@code spring.application.name}，多应用共用 Redis 时须各自配唯一前缀）。
 * 任务名是 Redis 锁键的一部分（{@code {prefix}:lock:{任务名}}），改名等于换锁，滚动发布期间新旧实例会各跑一次。
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.roundrobin")
public class RoundRobinProperties {

    /** 是否启用轮询调度，默认 false */
    private boolean enabled = false;

    /**
     * Redis 键前缀，多应用共用 Redis 时需唯一；
     * 未配置时按 {@code yomi:roundrobin:{spring.application.name}} 推导
     */
    private String storePrefix;

}
