package com.yomi.boot.java.qucik.starter.testcaptcha.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.qucik.starter.testcaptcha.dto.TestCaptchaChallengeResult;
import com.yomi.boot.java.qucik.starter.testcaptcha.service.TestCaptchaFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 验证码流程测试（业务自行封装接口的样板：组件不提供任何接口）
 * <p>
 * 前置：{@code yomi.captcha.enabled=true}；存储 auto（配了 Redis 走 Redis，否则本地）。
 * <ul>
 *     <li>GET /test/ds/test-captcha/generate?type=text|arithmetic</li>
 *     <li>POST /test/ds/test-captcha/verify?captchaId=..&amp;answer=..</li>
 * </ul>
 * 该路径已在 token/rbac 的放行列表内（{@code /test/ds/**}）。
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-captcha")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "yomi.captcha.enabled", havingValue = "true")
public class TestCaptchaFlowController {

    /** 验证码演示流程 */
    private final TestCaptchaFlowService flowService;

    /**
     * 生成挑战（type 取 text/arithmetic，缺省用配置的默认类型）；响应带 {@code Cache-Control: no-store}
     * <p>
     * 收字符串后走 {@code CaptchaType.parse}：MVC 的 {@code @RequestParam} 枚举转换大小写敏感，直接声明枚举会拒掉小写取值；
     * 显式传入非法取值报参数错误。
     */
    @GetMapping("/generate")
    public ResponseEntity<Response<TestCaptchaChallengeResult>> generate(@RequestParam(required = false) String type) {
        CaptchaType parsed = CaptchaType.parse(type, null);
        if (StringUtils.isNotBlank(type) && parsed == null) {
            throw new IllegalArgumentException("type 取值 text / arithmetic（当前 " + type + "）");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Response.success(flowService.generate(parsed)));
    }

    /**
     * 校验答案，返回 OK / MISMATCH / EXPIRED（业务登录接口内应做同样调用）
     */
    @PostMapping("/verify")
    public Response<String> verify(@RequestParam String captchaId, @RequestParam String answer) {
        return Response.success(flowService.verify(captchaId, answer).name());
    }

}
