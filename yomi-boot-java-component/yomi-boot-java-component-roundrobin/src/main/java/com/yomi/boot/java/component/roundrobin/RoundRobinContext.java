package com.yomi.boot.java.component.roundrobin;

/**
 * 轮询上下文：handler 据此感知取消；也是调度内部的取消载体——调度根上下文由
 * {@link RoundRobinRegistry} 持有、停机时取消，逐轮上下文以它为父（合并 max-hold / 丢锁取消）
 *
 * @author zwei
 */
public interface RoundRobinContext {

    /**
     * 是否应停止当前任务（含应用停机、丢锁、超过最长持锁）
     */
    boolean isCancelled();

}
