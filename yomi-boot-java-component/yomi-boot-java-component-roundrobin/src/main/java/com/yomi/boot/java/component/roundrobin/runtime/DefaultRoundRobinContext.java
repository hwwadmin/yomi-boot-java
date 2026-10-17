package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.RoundRobinContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可合并父调度取消与任务级取消
 *
 * @author zwei
 */
public final class DefaultRoundRobinContext implements RoundRobinContext {

    /** 生命周期级取消 */
    private final CancellationToken scheduleToken;

    /** 单轮任务级取消（如 max-hold） */
    private final AtomicBoolean taskCancelled = new AtomicBoolean();

    public DefaultRoundRobinContext(CancellationToken scheduleToken) {
        this.scheduleToken = scheduleToken;
    }

    /** 请求取消后续调度 */
    public void cancel() {
        taskCancelled.set(true);
    }

    /** 是否收到取消信号 */
    @Override
    public boolean isCancelled() {
        return scheduleToken.isCancelled() || taskCancelled.get();
    }

}
