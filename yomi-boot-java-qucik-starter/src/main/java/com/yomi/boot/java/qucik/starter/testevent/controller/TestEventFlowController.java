package com.yomi.boot.java.qucik.starter.testevent.controller;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.event.EventUtils;
import com.yomi.boot.java.component.event.consume.EventConsumeResult;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.model.WireEvent;
import com.yomi.boot.java.qucik.starter.testevent.TestEventTopics;
import com.yomi.boot.java.qucik.starter.testevent.service.TestEventDemoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
/**
 * 事件组件流程测试：发件箱写入、重复发布、{@link EventUtils} 模拟幂等消费。
 * <p>
 * 前置：{@code yomi.event.publisher-enabled=true}、{@code yomi.event.consumer-enabled=true}、
 * Redis（组件表需先手工执行仓库根目录 sql/mysql|pgsql/t_sys_event.sql）；经 Kafka 端到端另需 broker 及
 * {@link com.yomi.boot.java.qucik.starter.testevent.kafka.TestEventDemoKafkaListener}
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-event")
@RequiredArgsConstructor
public class TestEventFlowController {

    /** 事件发件箱演示 */
    private final TestEventDemoService demoService;

    @PostMapping("/utils-flow")
    public Response<List<String>> utilsFlow() {
        List<String> steps = new ArrayList<>();
        String eventId = EventUtils.newEventId();
        String payload = "{\"demo\":\"event-utils\"}";
        WireEvent event = EventUtils.wire(eventId, TestEventTopics.DEMO, payload);
        log.info("[事件测试流程] 开始 eventId={}", eventId);
        try {
            demoService.publish(event);
            OutboxStatus status = demoService.outboxStatus(eventId);
            Assert.isTrue(status == OutboxStatus.PENDING,
                    "发件箱状态应为 PENDING, 实际={}", status);
            step(steps, "1.发件箱写入: eventId=" + eventId + ", status=" + status);

            try {
                demoService.publish(event);
                throw new IllegalStateException("重复发布应失败");
            } catch (BizException duplicate) {
                step(steps, "2.重复发布: 已拒绝, code=" + duplicate.getErrorCode());
            }

            String wireJson = EventUtils.toJson(event);
            EventConsumeResult first = EventUtils.onMessage(wireJson, demoService::onDemoMessage);
            Assert.isTrue(first == EventConsumeResult.SUCCESS, "首次消费应 SUCCESS, 实际={}", first);
            Assert.isTrue(demoService.sideEffectCount(eventId) == 1,
                    "副作用次数应为 1, 实际={}", demoService.sideEffectCount(eventId));
            step(steps, "3.幂等消费: result=" + first + ", sideEffects=1");

            EventConsumeResult second = EventUtils.onMessage(wireJson, demoService::onDemoMessage);
            Assert.isTrue(second == EventConsumeResult.SUCCESS, "重复消费应 SUCCESS, 实际={}", second);
            Assert.isTrue(demoService.sideEffectCount(eventId) == 1,
                    "重复消费副作用仍应为 1, 实际={}", demoService.sideEffectCount(eventId));
            step(steps, "4.重复消费: result=" + second + ", sideEffects=1");

            step(steps, "5.流程通过");
            log.info("[事件测试流程] 结束");
            return Response.success(steps);
        } catch (Exception e) {
            log.error("[事件测试流程] 失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /** 记录演示流程步骤 */
    private static void step(List<String> steps, String detail) {
        steps.add(detail);
        log.info("[事件测试流程] {}", detail);
    }

}
