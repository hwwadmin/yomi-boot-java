package com.yomi.boot.java.component.roundrobin.config;

import lombok.Getter;

/**
 * Redis Key 拼装
 *
 * @author zwei
 */
@Getter
public class RoundRobinKeys {

    /** 形如 yomi:roundrobin:{applicationName} */
    private final String prefix;

    public RoundRobinKeys(String prefix) {
        this.prefix = prefix;
    }

    /** 任务执行频率在 Redis 中的键 */
    public String frequencyKey(String taskName) {
        return prefix + ":frequency:" + taskName;
    }

    /** 分布式互斥锁键 */
    public String lockKey(String taskName) {
        return prefix + ":lock:" + taskName;
    }

}
