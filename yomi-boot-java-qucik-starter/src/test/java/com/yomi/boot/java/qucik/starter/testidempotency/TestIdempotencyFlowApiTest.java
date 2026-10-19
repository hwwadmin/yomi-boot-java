package com.yomi.boot.java.qucik.starter.testidempotency;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等流程接口 HTTP 测试（请求者视角）
 * <p>
 * 运行前需手动启动应用，且 {@code yomi.idempotency.enabled=true}、Redis 可用、已建 sys_idempotency_record 表
 *
 * @author zwei
 */
class TestIdempotencyFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String FLOW_PATH = "/test/ds/test-idempotency/flow";

    private static final List<String> STEP_PREFIXES = List.of(
            "1.首次调用: ", "2.重复同键: ", "3.新键调用: ", "4.流程通过");

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void flowApiPassesFourSteps() {
        assertFourSteps(requestFlow(FLOW_PATH));
    }

    /** 调用演示 HTTP 接口 */
    private List<String> requestFlow(String path) {
        ResponseEntity<String> response = restClient.post().uri(path).retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP状态码应为200，实际响应: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertFalse(body.isEmpty(), "响应体无法解析: " + response.getBody());
        assertEquals(StatusCode.succeed.getCode(), ((Number) body.get("code")).intValue(),
                "响应code应为成功，实际响应: " + response.getBody());
        Object data = body.get("data");
        assertInstanceOf(List.class, data, "响应data应为步骤明细数组，实际响应: " + response.getBody());
        return ((List<?>) data).stream().map(String::valueOf).toList();
    }

    /** 断言测试条件：FourSteps */
    private void assertFourSteps(List<String> steps) {
        assertEquals(STEP_PREFIXES.size(), steps.size(), "步骤数应为4，实际: " + steps);
        for (int i = 0; i < STEP_PREFIXES.size(); i++) {
            assertTrue(steps.get(i).startsWith(STEP_PREFIXES.get(i)), "第" + (i + 1) + "步前缀不符: " + steps.get(i));
        }
        assertTrue(steps.get(1).contains("与首次结果一致"), "第2步应说明重放一致: " + steps.get(1));
    }

}
