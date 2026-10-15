package com.yomi.boot.java.component.roundrobin.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度根取消令牌，停机时由 {@link com.yomi.boot.java.component.roundrobin.spring.RoundRobinLifecycle} 触发
 *
 * @author zwei
 */
public final class CancellationToken {

    /** 是否已请求取消本次执行 */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** 请求取消后续调度 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 是否已取消 */
    public boolean isCancelled() {
        return cancelled.get();
    }

}
