package com.yomi.boot.java.component.httplog.store;

import com.yomi.boot.java.component.httplog.model.HttpLogRecord;

import java.util.List;

/**
 * 批量落库端口：默认实现为批量写入 {@code t_sys_http_log}
 * <p>宿主注册自己的 Bean 后以宿主的实现为准（{@code @ConditionalOnMissingBean}），可写入异构存储（ClickHouse/ES 等）。
 *
 * @author zwei
 */
public interface HttpLogStore {

    /**
     * 批量写入
     * <p>内部完成有限重试（重试耗尽抛异常，由消费循环按失败丢弃并计数）。
     */
    void writeBatch(List<HttpLogRecord> batch);

}
