package com.yomi.boot.java.qucik.starter.testoss;

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
 * OSS 门面流程 HTTP 测试（请求者视角），只断言步骤结构与成功响应，不关心 OSS 实现类型。
 * <p>
 * 运行前需手动启动应用（{@code mvn spring-boot:run -pl yomi-boot-java-qucik-starter}），
 * 且 {@code yomi.oss.enabled=true}、默认测试桶 {@code yomi-test} 已存在
 * （dev 配置已指向本地 RustFS，桶建在本地容器里即可）。
 *
 * @author zwei
 */
class TestOssFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String FLOW_PATH = "/test/ds/test-oss/flow";

    private static final int EXPECTED_STEPS = 7;

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void ossFlowApiPassesSevenSteps() {
        List<String> steps = requestFlow(FLOW_PATH);
        assertEquals(EXPECTED_STEPS, steps.size(), "步骤数应为7，实际: " + steps);
        assertTrue(steps.get(0).startsWith("1.上传: "), "第1步: " + steps.get(0));
        assertTrue(steps.get(1).startsWith("2.存在: "), "第2步: " + steps.get(1));
        assertTrue(steps.get(2).startsWith("3.元数据: "), "第3步: " + steps.get(2));
        assertTrue(steps.get(3).startsWith("4.下载: "), "第4步: " + steps.get(3));
        assertTrue(steps.get(4).startsWith("5.公开URL: ") || steps.get(4).startsWith("5.跳过: "),
                "第5步: " + steps.get(4));
        assertTrue(steps.get(5).startsWith("6.预签名: "), "第6步: " + steps.get(5));
        assertEquals("7.流程通过", steps.get(6), "第7步: " + steps.get(6));
    }

    /** 调用演示 HTTP 接口 */
    private List<String> requestFlow(String path) {
        ResponseEntity<String> response = restClient.get().uri(path).retrieve().toEntity(String.class);
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
