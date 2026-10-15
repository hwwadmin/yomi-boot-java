package com.yomi.boot.java.qucik.starter.testredis.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testredis.service.TestRedisFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Redis 组件流程测试（锁 + 限流）。
 * <p>
 * 前置：Redis、{@code yomi.ratelimit} 已配置（开发环境 application-dev.yml 已示例）。
 * <ul>
 *     <li>POST /test/ds/test-redis/flow</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-redis")
@RequiredArgsConstructor
public class TestRedisFlowController {

    /** Redis 能力演示 */
    private final TestRedisFlowService flowService;

    @PostMapping("/flow")
    public Response<List<String>> flow() {
        log.info("[Redis测试流程] 开始");
        List<String> steps = flowService.runFlow();
        log.info("[Redis测试流程] 结束");
        return Response.success(steps);
    }

}
