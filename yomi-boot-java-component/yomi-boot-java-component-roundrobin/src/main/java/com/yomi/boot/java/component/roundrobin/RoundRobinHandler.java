package com.yomi.boot.java.component.roundrobin;

/**
 * 单轮轮询任务执行体，异常由框架记录日志，不终止调度环
 *
 * @author zwei
 */
@FunctionalInterface
public interface RoundRobinHandler {

    /** 应适时检查 {@link RoundRobinContext#isCancelled()} */
    void run(RoundRobinContext ctx) throws Exception;

}
