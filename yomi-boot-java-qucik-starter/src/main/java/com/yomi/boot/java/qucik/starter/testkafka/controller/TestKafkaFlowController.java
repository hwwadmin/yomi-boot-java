package com.yomi.boot.java.qucik.starter.testkafka.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testkafka.service.TestKafkaFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Kafka 组件流程测试（{@link com.yomi.boot.java.component.kafka.KafkaUtils} 同步发送）。
 * <p>
 * 前置：Kafka {@code spring.kafka.bootstrap-servers} 可达、Topic {@code yomi.test.kafka.demo} 可自动创建。
 * <ul>
 *     <li>POST /test/ds/test-kafka/flow</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-kafka")
@RequiredArgsConstructor
public class TestKafkaFlowController {

    /** Kafka 演示流程 */
    private final TestKafkaFlowService flowService;

    @PostMapping("/flow")
    public Response<List<String>> flow() {
        log.info("[Kafka测试流程] 开始");
        List<String> steps = flowService.runFlow();
        log.info("[Kafka测试流程] 结束");
        return Response.success(steps);
    }

}
