package com.yomi.boot.java.component.event.relay;

import com.yomi.boot.java.component.event.repository.IEventOutboxDlqRepository;
import com.yomi.boot.java.component.event.support.EventKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * DLQ 运维：人工恢复重试计数（对齐 Go ResetRetryCountInTimeRange）
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class EventOutboxDlqAdmin {

    /** 数据访问 */
    private final IEventOutboxDlqRepository dlqRepository;

    /** 当前服务标识 */
    private final EventKeys eventKeys;

    /** 将创建时间在 [from, to] 内的 DLQ 重试计数清零 */
    public long resetRetryCountInTimeRange(Instant from, Instant to) {
        long affected = dlqRepository.resetRetryCountInRange(eventKeys.getServiceName(), from, to);
        log.info("[事件]DLQ 重试计数已重置 service={}, from={}, to={}, affected={}",
                eventKeys.getServiceName(), from, to, affected);
        return affected;
    }

}
