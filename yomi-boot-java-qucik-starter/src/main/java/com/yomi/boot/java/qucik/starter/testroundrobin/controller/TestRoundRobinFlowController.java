package com.yomi.boot.java.qucik.starter.testroundrobin.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testroundrobin.service.TestRoundRobinFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RoundRobin 组件流程测试（{@code @RoundRobinTask} 声明式任务）。
 * <p>
 * 前置：{@code yomi.roundrobin.enabled=true}、Redis。
 * <ul>
 *     <li>POST /test/ds/test-roundrobin/flow</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-roundrobin")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.roundrobin.enabled", havingValue = "true")
public class TestRoundRobinFlowController {

    /** 轮询任务演示 */
    private final TestRoundRobinFlowService flowService;

    @PostMapping("/flow")
    public Response<List<String>> flow() {
        log.info("[RoundRobin测试流程] 开始");
        List<String> steps = flowService.runFlow();
        log.info("[RoundRobin测试流程] 结束");
        return Response.success(steps);
    }

}
