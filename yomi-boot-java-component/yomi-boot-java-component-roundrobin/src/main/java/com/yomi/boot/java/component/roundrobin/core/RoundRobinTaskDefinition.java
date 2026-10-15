package com.yomi.boot.java.component.roundrobin.core;

import com.yomi.boot.java.component.roundrobin.runtime.RoundRobinHandler;
import lombok.Getter;

/**
 * 已注册任务：{@link RoundRobinConfig} + {@link RoundRobinHandler}
 *
 * @author zwei
 */
@Getter
public class RoundRobinTaskDefinition {

    /** 频率、锁、jitter 等 */
    private final RoundRobinConfig config;
    /** 被调度的任务元数据 */
    private final RoundRobinHandler task;

    public RoundRobinTaskDefinition(RoundRobinConfig config, RoundRobinHandler task) {
        this.config = config;
        this.task = task;
    }

}
