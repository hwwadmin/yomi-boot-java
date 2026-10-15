package com.yomi.boot.java.component.httplog.queue;

import java.time.Duration;

/**
 * 日志消费端：从介质（或本地缓冲）批量取出并回调
 * <p>生命周期由组件 SmartLifecycle 驱动（start/stop），实现类不实现 {@link AutoCloseable}。
 *
 * @author zwei
 */
public interface HttpLogConsumer {

    /** 启动消费循环 */
    void start(HttpLogBatchHandler handler);

    /** 停止消费循环（尽量处理完在途批次） */
    void stop(Duration timeout);

}
