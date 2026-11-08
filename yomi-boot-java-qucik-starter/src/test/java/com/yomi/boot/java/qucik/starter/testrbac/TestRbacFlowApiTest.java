package com.yomi.boot.java.qucik.starter.testrbac;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.qucik.starter.testrbac.dto.TestRbacFlowResult;
import com.yomi.boot.java.qucik.starter.testrbac.dto.TestRbacTokenResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBAC 流程：先跑 flow 准备数据，再对受保护接口做 HTTP 探测。
 * <p>
 * 运行前需启动应用且 {@code yomi.rbac.enabled=true}，并已执行 t_sys_rbac.sql。
 */
class TestRbacFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String FLOW_PATH = "/test/ds/test-rbac/flow";
    private static final String ISSUE_TOKEN_PATH = "/test/ds/test-rbac/issue-token";
    private static final String WHOAMI_PATH = "/test/ds/test-rbac/whoami";

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void flowPreparesDataAndHttpInterceptorHonorsRbac() {
        TestRbacFlowResult flow = requestFlow();
        String allowedToken = issueToken(flow.getAllowedUserId()).getToken();
        String deniedToken = issueToken(flow.getDeniedUserId()).getToken();
        assertNotEquals(allowedToken, deniedToken, "两个用户的令牌必须不同");
        assertEquals(flow.getAllowedUserId(), whoamiUserId(allowedToken));
        assertEquals(flow.getDeniedUserId(), whoamiUserId(deniedToken));

        assertProtectedForbidden(deniedToken);
        assertProtectedOk(allowedToken);
        assertProtectedTokenRequired();
    }

    /** 调用演示 HTTP 接口 */
    private TestRbacFlowResult requestFlow() {
        ResponseEntity<String> response = restClient.get().uri(FLOW_PATH).retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "flow HTTP: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertFalse(body.isEmpty(), "flow 响应无法解析: " + response.getBody());
        assertEquals(StatusCode.succeed.getCode(), intValue(body.get("code")),
                "flow 业务码: " + response.getBody());
        TestRbacFlowResult data = JsonUtils.parse(JsonUtils.toJson(body.get("data")), TestRbacFlowResult.class);
        assertNotNull(data);
        assertTrue(data.getSteps().stream().anyMatch(s -> s.contains("流程通过")), "flow 步骤: " + data.getSteps());
        return data;
    }

    /** 从 whoami 响应解析用户 ID */
    private long whoamiUserId(String token) {
        String uri = UriComponentsBuilder.fromPath(WHOAMI_PATH)
                .queryParam("token", token)
                .build()
                .toUriString();
        ResponseEntity<String> response = restClient.get().uri(uri).retrieve().toEntity(String.class);
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertEquals(StatusCode.succeed.getCode(), intValue(body.get("code")),
                "whoami: " + response.getBody());
        return longValue(body.get("data"));
    }

    /** 从 JSON 节点读取 int */
    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /** 从 JSON 节点读取 long */
    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /** 为演示用户签发令牌 */
    private TestRbacTokenResult issueToken(long userId) {
        ResponseEntity<String> response = restClient.get()
                .uri(ISSUE_TOKEN_PATH + "?userId=" + userId)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "issue-token HTTP: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertEquals(StatusCode.succeed.getCode(), intValue(body.get("code")),
                "issue-token 业务码: " + response.getBody());
        TestRbacTokenResult data = JsonUtils.parse(JsonUtils.toJson(body.get("data")), TestRbacTokenResult.class);
        assertNotNull(data);
        assertEquals(userId, data.getUserId());
        assertNotNull(data.getToken());
        return data;
    }

    /** 断言测试条件：ProtectedOk */
    private void assertProtectedOk(String token) {
        ResponseEntity<String> response = restClient.get()
                .uri(TestRbacDemoConstants.PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "有权限探测: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertEquals(StatusCode.succeed.getCode(), intValue(body.get("code")),
                "有权限业务码: " + response.getBody());
    }

    /** 断言测试条件：ProtectedForbidden */
    private void assertProtectedForbidden(String token) {
        ResponseEntity<String> response = restClient.get()
                .uri(TestRbacDemoConstants.PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "无权限 HTTP: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertEquals("RBAC_FORBIDDEN", String.valueOf(body.get("errorCode")), "无权限 errorCode: " + response.getBody());
    }

    /** 断言测试条件：ProtectedTokenRequired */
    private void assertProtectedTokenRequired() {
        ResponseEntity<String> response = restClient.get()
                .uri(TestRbacDemoConstants.PROTECTED_PATH)
                .retrieve()
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "缺 token HTTP: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertEquals("TOKEN_REQUIRED", String.valueOf(body.get("errorCode")), "缺 token errorCode: " + response.getBody());
    }

}
