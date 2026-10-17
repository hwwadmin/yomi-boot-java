package com.yomi.boot.java.component.roundrobin;

/**
 * 单次 tick 内业务可见的上下文（调度停止或丢锁 / 超过 max-hold 时取消）
 *
 * @author zwei
 */
public interface RoundRobinContext {

    /**
     * 是否应停止当前任务（含应用停机、丢锁、超过最长持锁）
     */
    boolean isCancelled();

}
