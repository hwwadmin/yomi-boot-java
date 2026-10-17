package com.yomi.boot.java.component.httplog.queue;

import com.yomi.boot.java.component.httplog.model.HttpLogRecord;

import java.time.Duration;

/**
 * 日志生产端：请求线程唯一入口
 * <p>实现内部 = 进程内有界缓冲（+ 外部队列模式下的发送器）；{@link #offer} 不阻塞调用线程。
 * 生命周期由组件 SmartLifecycle 驱动（flush → stop），实现类通过这两个方法参与生命周期。
 *
 * @author zwei
 */
public interface HttpLogSink {

    /**
     * 非阻塞投递
     *
     * @return false=缓冲已满（记录被丢弃并计数），调用方不重试
     */
    boolean offer(HttpLogRecord record);

    /** 停机排空：发送/落库剩余缓冲，超时放弃并计数 */
    void flush(Duration timeout);

}
