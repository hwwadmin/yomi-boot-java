package com.yomi.boot.java.qucik.starter.testcaptcha;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证码流程 HTTP 测试。
 * <p>
 * 运行前需启动应用，且 {@code yomi.captcha.enabled=true}。
 * 测试不获取答案（组件不暴露答案），改用"错误答案 → MISMATCH，再验 → EXPIRED"验证一次性消费。
 */
class TestCaptchaFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String GENERATE_PATH = "/test/ds/test-captcha/generate";
    private static final String VERIFY_PATH = "/test/ds/test-captcha/verify";

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void textChallengeConsumedOnce() {
        Map<String, Object> challenge = generate("text");
        String captchaId = String.valueOf(challenge.get("captchaId"));
        assertFalse(captchaId.isBlank(), "captchaId: " + challenge);
        assertEquals("TEXT", String.valueOf(challenge.get("type")));
        String image = String.valueOf(challenge.get("image"));
        assertTrue(image.startsWith("data:image/png;base64,"), "image 应为 data URI");
        assertTrue(image.length() > 1000, "图片数据过短: " + image.length());
        assertNotNull(challenge.get("expireAt"), "expireAt 应下发（毫秒字符串）");

        assertEquals("MISMATCH", verify(captchaId, "__wrong__"));
        assertEquals("EXPIRED", verify(captchaId, "__wrong__"), "校验一次后挑战即消费");
    }

    @Test
    void arithmeticChallengeGenerates() {
        Map<String, Object> challenge = generate("arithmetic");
        assertEquals("ARITHMETIC", String.valueOf(challenge.get("type")));
        assertTrue(String.valueOf(challenge.get("image")).startsWith("data:image/png;base64,"));
        // 算术结果恒 ≥2 且排除 0/1，因此 "0" 必然是错误答案
        assertEquals("MISMATCH", verify(String.valueOf(challenge.get("captchaId")), "0"));
    }

    @Test
    void invalidExplicitTypeIsParameterError() {
        ResponseEntity<String> response = restClient.get().uri(GENERATE_PATH + "?type=foo")
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertEquals(StatusCode.parameterError.getCode(), ((Number) body.get("code")).intValue(),
                "显式非法 type 应报参数错误而非静默回退: " + response.getBody());
    }

    /** 调用生成接口并返回 data 节点 */
    private Map<String, Object> generate(String type) {
        ResponseEntity<String> response = restClient.get().uri(GENERATE_PATH + "?type=" + type)
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP: " + response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl(), "验证码响应不应被缓存");
        Map<String, Object> body = body(response.getBody());
        Object data = body.get("data");
        assertInstanceOf(Map.class, data, "data 应为对象: " + response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> challenge = (Map<String, Object>) data;
        return challenge;
    }

    /** 调用校验接口并返回结果名 */
    private String verify(String captchaId, String answer) {
        ResponseEntity<String> response = restClient.post()
                .uri(VERIFY_PATH + "?captchaId=" + captchaId + "&answer=" + answer)
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP: " + response.getBody());
        return String.valueOf(body(response.getBody()).get("data"));
    }

    /** 解析响应信封并断言成功码 */
    private static Map<String, Object> body(String json) {
        Map<String, Object> body = JsonUtils.jsonStr2Map(json);
        assertFalse(body.isEmpty(), "响应无法解析: " + json);
        assertEquals(StatusCode.succeed.getCode(), ((Number) body.get("code")).intValue(), "业务码: " + json);
        return body;
    }

}
