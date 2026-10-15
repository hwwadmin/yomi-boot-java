package com.yomi.boot.java.qucik.starter.testidempotency.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.idempotency.IdempotencyUtils;
import com.yomi.boot.java.qucik.starter.testidempotency.dto.TestIdempotencyInvokeResult;
import com.yomi.boot.java.qucik.starter.testidempotency.service.TestIdempotencyDemoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 幂等组件流程测试：一次调用完成「首调 → 同键重放 → 新键再调」并逐步断言。
 * <p>
 * 前置：{@code yomi.idempotency.enabled=true}、Redis/Redisson（组件表由启动期自动创建，脚本 component-idempotency/sql/mysql|pgsql/sys_idempotency_record.sql）
 * <ul>
 *     <li>POST /test/ds/test-idempotency/annotation：{@link com.yomi.boot.java.component.idempotency.annotation.Idempotent}</li>
 *     <li>POST /test/ds/test-idempotency/utils：{@link IdempotencyUtils}</li>
 * </ul>
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-idempotency")
@RequiredArgsConstructor
public class TestIdempotencyFlowController {

    /** 演示：注解幂等流程标识 */
    private static final String FLOW_ANNOTATION = "annotation";
    /** 演示：工具类幂等流程标识 */
    private static final String FLOW_UTILS = "utils";
    /** 演示：工具类幂等命名空间 */
    private static final String NAMESPACE_UTILS = "test:idempotency:utils";

    /** 注解与工具类演示服务 */
    private final TestIdempotencyDemoService demoService;

    @PostMapping("/annotation")
    public Response<List<String>> annotationFlow() {
        return runFlow(FLOW_ANNOTATION, key -> demoService.invokeByAnnotation(key));
    }

    @PostMapping("/utils")
    public Response<List<String>> utilsFlow() {
        return runFlow(FLOW_UTILS, key -> IdempotencyUtils.execute(
                NAMESPACE_UTILS,
                key,
                TestIdempotencyInvokeResult.class,
                () -> demoService.recordSideEffect(NAMESPACE_UTILS, key)
        ));
    }

    /** 执行演示流程并逐步断言 */
    private Response<List<String>> runFlow(String flow, java.util.function.Function<String, TestIdempotencyInvokeResult> invoker) {
        List<String> steps = new ArrayList<>();
        String flowKey = IdempotencyUtils.newKey();
        log.info("[幂等测试流程][{}] 开始, flowKey={}", flow, flowKey);
        try {
            TestIdempotencyInvokeResult first = invoker.apply(flowKey);
            assertResult(first, 1, "[1.首次调用]");
            step(steps, flow, "1.首次调用: flowKey=" + flowKey + ", " + first);

            TestIdempotencyInvokeResult second = invoker.apply(flowKey);
            assertSameReplay(first, second, "[2.重复同键]");
            step(steps, flow, "2.重复同键: 与首次结果一致, " + second);

            String otherKey = IdempotencyUtils.newKey();
            TestIdempotencyInvokeResult third = invoker.apply(otherKey);
            assertResult(third, 1, "[3.新键调用]");
            Assert.isTrue(!Objects.equals(first.getRunId(), third.getRunId()),
                    "[3.新键调用] runId 不应与首次相同: first={}, third={}", first.getRunId(), third.getRunId());
            step(steps, flow, "3.新键调用: flowKey=" + otherKey + ", " + third);

            step(steps, flow, "4.流程通过");
            log.info("[幂等测试流程][{}] 结束", flow);
            return Response.success(steps);
        } catch (Exception e) {
            log.error("[幂等测试流程][{}] 失败于第{}步: {}", flow, steps.size() + 1,
                    StringUtils.normalizeSpace(e.getMessage()));
            throw e;
        }
    }

    /** 断言演示调用副作用计数 */
    private static void assertResult(TestIdempotencyInvokeResult result, int expectedCount, String step) {
        Assert.nonNull(result, "{} 返回为空", step);
        Assert.nonNull(result.getRunId(), "{} runId 为空", step);
        Assert.isTrue(result.getSideEffectCount() == expectedCount,
                "{} sideEffectCount 应为 {}, 实际={}", step, expectedCount, result.getSideEffectCount());
    }

    /** 断言幂等重放结果一致 */
    private static void assertSameReplay(TestIdempotencyInvokeResult first, TestIdempotencyInvokeResult second, String step) {
        assertResult(second, 1, step);
        Assert.isTrue(Objects.equals(first.getRunId(), second.getRunId()),
                "{} runId 不一致: first={}, second={}", step, first.getRunId(), second.getRunId());
        Assert.isTrue(first.getSideEffectCount() == second.getSideEffectCount(),
                "{} sideEffectCount 不一致", step);
    }

    /** 记录演示流程步骤 */
    private static void step(List<String> steps, String flow, String detail) {
        steps.add(detail);
        log.info("[幂等测试流程][{}] {}", flow, detail);
    }

}
