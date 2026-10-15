package com.yomi.boot.java.component.event.support;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 事件组件 Redis 键与 RoundRobin 任务名
 *
 * @author zwei
 */
@Getter
public class EventKeys {

    /** 多租户隔离：锁键、轮询任务名等均带此服务名 */
    private final String serviceName;

    public EventKeys(String serviceName) {
        if (StringUtils.isBlank(serviceName)) {
            throw new IllegalArgumentException("event serviceName 不能为空");
        }
        this.serviceName = serviceName.trim();
    }

    /** 消费幂等分布式锁 */
    public String consumeLockKey(String eventId) {
        return "event:receiver:lock:" + serviceName + ":" + eventId;
    }

    /** 轮询任务名后缀：{@code 前缀 + "_" + serviceName} */
    public String roundRobinName(String suffix) {
        return StringUtils.trim(suffix) + "_" + serviceName;
    }

}
