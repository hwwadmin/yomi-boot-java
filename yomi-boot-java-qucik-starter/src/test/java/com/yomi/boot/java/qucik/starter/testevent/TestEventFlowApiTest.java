package com.yomi.boot.java.qucik.starter.testevent;

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
 * 事件流程接口 HTTP 测试（请求者视角）
 * <p>
 * 运行前需手动启动应用，且 event 开关、Redis、t_sys_event 各表已就绪
 *
 * @author zwei
 */
class TestEventFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String UTILS_FLOW_PATH = "/test/ds/test-event/utils-flow";

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void utilsFlowApiPassesFiveSteps() {
        List<String> steps = requestFlow(UTILS_FLOW_PATH);
        assertEquals(5, steps.size(), "步骤数应为5，实际: " + steps);
        assertTrue(steps.get(0).startsWith("1.发件箱写入:"), steps.get(0));
        assertTrue(steps.get(1).startsWith("2.重复发布:"), steps.get(1));
        assertTrue(steps.get(2).startsWith("3.幂等消费:"), steps.get(2));
        assertTrue(steps.get(3).startsWith("4.重复消费:"), steps.get(3));
        assertEquals("5.流程通过", steps.get(4));
        assertTrue(steps.get(2).contains("sideEffects=1"), steps.get(2));
        assertTrue(steps.get(3).contains("sideEffects=1"), steps.get(3));
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

}
