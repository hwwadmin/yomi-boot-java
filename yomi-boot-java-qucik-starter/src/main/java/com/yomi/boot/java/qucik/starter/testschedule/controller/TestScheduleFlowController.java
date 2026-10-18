package com.yomi.boot.java.qucik.starter.testschedule.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testschedule.service.TestScheduleFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群定时任务流程测试（{@code ClusterTask} 装配的周期任务）。
 * <p>
 * 前置：Redis（任务锁与计数器）。
 * <ul>
 *     <li>POST /test/ds/test-schedule/flow</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-schedule")
@RequiredArgsConstructor
public class TestScheduleFlowController {

    /** 定时任务演示 */
    private final TestScheduleFlowService flowService;

    @PostMapping("/flow")
    public Response<List<String>> flow() {
        log.info("[集群定时任务测试流程] 开始");
        List<String> steps = flowService.runFlow();
        log.info("[集群定时任务测试流程] 结束");
        return Response.success(steps);
    }

}
