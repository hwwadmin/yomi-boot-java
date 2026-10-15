package com.yomi.boot.java.qucik.starter.testevent.service;

import com.yomi.boot.java.component.event.EventUtils;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.component.event.IEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件组件演示：发件箱写入与模拟消费副作用
 *
 * @author zwei
 */
@Service
@RequiredArgsConstructor
public class TestEventDemoService {

    /** 查询发件箱状态 */
    private final IEventService eventService;

    /** 模拟消费次数（演示用） */
    private final Map<String, AtomicInteger> consumeSideEffects = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public void publish(WireEvent event) {
        EventUtils.publish(event);
    }

    /** 查询演示事件发件箱状态 */
    public OutboxStatus outboxStatus(String eventId) {
        return eventService.outboxStatus(eventId);
    }

    /** 收件箱幂等消费演示 */
    public void onDemoMessage(WireEvent event) {
        consumeSideEffects.computeIfAbsent(event.getEventId(), k -> new AtomicInteger()).incrementAndGet();
    }

    /** 消费副作用计数（测试观测） */
    public int sideEffectCount(String eventId) {
        AtomicInteger counter = consumeSideEffects.get(eventId);
        return counter == null ? 0 : counter.get();
    }

}
