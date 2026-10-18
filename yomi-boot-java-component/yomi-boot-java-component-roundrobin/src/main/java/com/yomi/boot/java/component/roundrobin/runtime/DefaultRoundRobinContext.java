package com.yomi.boot.java.component.roundrobin.runtime;

import com.yomi.boot.java.component.roundrobin.RoundRobinContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认上下文实现：合并父上下文与本级取消；{@link #root()} 即调度根上下文（停机时由
 * {@link com.yomi.boot.java.component.roundrobin.RoundRobinRegistry} 取消），逐轮上下文以它为父
 *
 * @author zwei
 */
public final class DefaultRoundRobinContext implements RoundRobinContext {

    /** 父上下文；null = 调度根 */
    private final RoundRobinContext parent;

    /** 本级取消（根 = 停机取消；单轮 = max-hold / 丢锁取消） */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private DefaultRoundRobinContext() {
        this.parent = null;
    }

    /** 创建子上下文（父的取消对本级可见） */
    public DefaultRoundRobinContext(RoundRobinContext parent) {
        this.parent = Objects.requireNonNull(parent, "父上下文不能为空；调度根请用 DefaultRoundRobinContext.root()");
    }

    /** 调度根上下文 */
    public static DefaultRoundRobinContext root() {
        return new DefaultRoundRobinContext();
    }

    /** 请求本级取消 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 本级或任一祖先已取消 */
    @Override
    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

}
