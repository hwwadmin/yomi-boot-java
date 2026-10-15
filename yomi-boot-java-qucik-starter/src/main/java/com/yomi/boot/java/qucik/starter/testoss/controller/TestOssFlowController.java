package com.yomi.boot.java.qucik.starter.testoss.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.qucik.starter.testoss.service.TestOssFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OSS 门面流程测试：一次调用验证 {@link com.yomi.boot.java.component.oss.OssStorage} 主要 API。
 * <p>
 * 前置：{@code yomi.oss.enabled=true}、测试桶已存在（默认 {@link TestOssFlowService#DEFAULT_TEST_BUCKET}）。
 * 不校验具体 provider 与 endpoint 配置。
 * <ul>
 *     <li>GET /test/ds/test-oss/flow：可选 query {@code bucket} 覆盖默认测试桶</li>
 * </ul>
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-oss")
@RequiredArgsConstructor
public class TestOssFlowController {

    /** OSS 上传下载演示 */
    private final TestOssFlowService testOssFlowService;

    @GetMapping("/flow")
    public Response<List<String>> flow(@RequestParam(required = false) String bucket) {
        String resolved = StringUtils.isNotBlank(bucket) ? bucket.trim() : TestOssFlowService.DEFAULT_TEST_BUCKET;
        log.info("[OSS测试流程] 开始, bucket={}", resolved);
        try {
            List<String> steps = testOssFlowService.runFlow(resolved);
            log.info("[OSS测试流程] 完成, bucket={}, steps={}", resolved, steps.size());
            return Response.success(steps);
        } catch (RuntimeException e) {
            log.error("[OSS测试流程] 失败, bucket={}, msg={}", resolved, StringUtils.normalizeSpace(e.getMessage()));
            throw e;
        }
    }

}
