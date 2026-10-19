package com.yomi.boot.java.qucik.starter.testtable;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试表流程接口：请求者视角的HTTP测试用例，验证 增 → 查 → 改 → 查 → 删 → 查 六步全部通过，
 * 且接口可重复调用（每次调用数据自清理，互不干扰）
 * <p>
 * 运行前需手动启动应用（mvn -o spring-boot:run -pl yomi-boot-java-qucik-starter），
 * 并保证 test_table 表已创建（yomi-boot-java-qucik-starter/sql/mysql|pgsql/test_table.sql）
 *
 * @author zwei
 */
class TestTableFlowApiTest {

    private static final String BASE_URL = "http://127.0.0.1:28080";
    private static final String DAO_PATH = "/test/ds/test-table/dao";
    private static final String XML_PATH = "/test/ds/test-table/xml";

    private static final List<String> STEP_PREFIXES = List.of(
            "1.新增: ", "2.查询: ", "3.更新: ", "4.复查: ", "5.删除: ", "6.复查: ");
    private static final Pattern STEP_ID = Pattern.compile("id=(\\d+)");

    private final RestClient restClient = RestClient.create(BASE_URL);

    @Test
    void daoFlowApiRunsSixStepsAndIsRepeatable() {
        assertSixSteps(requestFlow(DAO_PATH));
    }

    @Test
    void xmlFlowApiRunsSixStepsAndIsRepeatable() {
        assertSixSteps(requestFlow(XML_PATH));
    }

    /**
     * 请求流程接口，断言HTTP状态与响应码为成功，返回六个步骤明细
     */
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

    /**
     * 断言六步明细的顺序、内容与id一致性
     */
    private void assertSixSteps(List<String> steps) {
        assertEquals(STEP_PREFIXES.size(), steps.size(), "步骤数应为6，实际: " + steps);
        for (int i = 0; i < STEP_PREFIXES.size(); i++) {
            assertTrue(steps.get(i).startsWith(STEP_PREFIXES.get(i)), "第" + (i + 1) + "步前缀不符: " + steps.get(i));
        }
        assertTrue(steps.get(5).endsWith("流程通过"), "末步应确认流程通过: " + steps.get(5));
        assertEquals(extractId(steps.get(0)), extractId(steps.get(4)), "删除的id与新增的id不一致: " + steps);
    }

    /** 从创建表响应解析主键 ID */
    private static String extractId(String step) {
        Matcher matcher = STEP_ID.matcher(step);
        assertTrue(matcher.find(), "步骤中未找到id: " + step);
        return matcher.group(1);
    }

}
