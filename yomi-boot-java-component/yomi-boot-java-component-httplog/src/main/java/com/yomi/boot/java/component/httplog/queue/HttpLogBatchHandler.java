package com.yomi.boot.java.component.httplog.queue;

import com.yomi.boot.java.component.httplog.model.HttpLogRecord;

import java.util.List;

/**
 * 批量处理回调
 * <p>重试与丢弃策略由消费循环内部闭环（有限重试后丢弃并计数），回调无返回值。
 *
 * @author zwei
 */
@FunctionalInterface
public interface HttpLogBatchHandler {

    /**
     * 处理一批记录
     * <p>允许抛出异常：由消费循环按"失败"计数并按实现策略处理（丢弃，不无限重试）。
     */
    void onBatch(List<HttpLogRecord> batch);

}
