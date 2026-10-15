package com.yomi.boot.java.qucik.starter.testtoken.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testtoken.service.TestTokenFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Token 组件流程测试。
 * <p>
 * 前置：{@code yomi.token.enabled=true}、Redis。
 * <ul>
 *     <li>POST /test/ds/test-token/flow</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-token")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.token.enabled", havingValue = "true")
public class TestTokenFlowController {

    /** 令牌签发校验演示 */
    private final TestTokenFlowService flowService;

    @PostMapping("/flow")
    public Response<List<String>> flow() {
        log.info("[Token测试流程] 开始");
        List<String> steps = flowService.runFlow();
        log.info("[Token测试流程] 结束");
        return Response.success(steps);
    }

}
