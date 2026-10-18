package com.yomi.boot.java.qucik.starter.testschedule;

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
 * 集群定时任务流程 HTTP 测试。
 * <p>
 * 运行前需启动应用，且 Redis 可用。
 */
class TestScheduleFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String FLOW_PATH = "/test/ds/test-schedule/flow";

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void flowApiPassesThreeSteps() {
        List<String> steps = requestFlow();
        assertEquals(3, steps.size(), "步骤数应为3，实际: " + steps);
        assertTrue(steps.get(0).startsWith("1.计数重置:"), steps.get(0));
        assertTrue(steps.get(1).startsWith("2.定时任务:"), steps.get(1));
        assertEquals("3.流程通过", steps.get(2));
    }

    /** 调用演示 HTTP 接口 */
    private List<String> requestFlow() {
        ResponseEntity<String> response = restClient.post().uri(FLOW_PATH).retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "HTTP: " + response.getBody());
        Map<String, Object> body = JsonUtils.jsonStr2Map(response.getBody());
        assertFalse(body.isEmpty(), "响应无法解析: " + response.getBody());
        assertEquals(StatusCode.succeed.getCode(), ((Number) body.get("code")).intValue(),
                "业务码: " + response.getBody());
        Object data = body.get("data");
        assertInstanceOf(List.class, data, "data 应为数组: " + response.getBody());
        return ((List<?>) data).stream().map(String::valueOf).toList();
    }

}
