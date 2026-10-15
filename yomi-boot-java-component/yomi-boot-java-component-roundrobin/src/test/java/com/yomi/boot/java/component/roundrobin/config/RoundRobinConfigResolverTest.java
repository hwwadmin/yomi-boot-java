package com.yomi.boot.java.component.roundrobin.config;

import com.yomi.boot.java.component.roundrobin.annotation.RoundRobinTask;
import com.yomi.boot.java.component.roundrobin.core.RoundRobinConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** execWatchdog 解析：仅接受 true/false，其它字符串应报错 */
class RoundRobinConfigResolverTest {

    /** 任务简介从注解进入启动日志，需去空白；注解默认空串时打印占位符 */
    @Test
    void descriptionIsResolvedAndTrimmed() throws Exception {
        RoundRobinConfig config = resolve("described");

        assertEquals("清理超时订单", config.getDescription());
        assertTrue(config.toLogSummary().contains("description=清理超时订单"), config.toLogSummary());
    }

    @Test
    void missingDescriptionPrintsPlaceholder() throws Exception {
        assertTrue(resolve("noDescription").toLogSummary().contains("description=未填写"),
                resolve("noDescription").toLogSummary());
    }

    @Test
    void trimsAndAcceptsCaseInsensitive() throws Exception {
        assertTrue(resolve("whitespaceTrue").isExecWatchdog());
        assertFalse(resolve("upperCaseFalse").isExecWatchdog());
    }

    @Test
    void blankFallsBackToDefaultWatchdog() throws Exception {
        assertTrue(resolve("blank").isExecWatchdog(), "空串/空白=不覆盖默认（默认 true）");
    }

    @Test
    void invalidValueFailsFastInsteadOfSilentlyDisablingWatchdog() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> resolve("invalid"));

        assertTrue(ex.getMessage().contains("execWatchdog"), ex.getMessage());
        assertTrue(ex.getMessage().contains("true/false"), ex.getMessage());
    }

    /** 解析并合并 RoundRobin 配置 */
    private static RoundRobinConfig resolve(String fixtureMethod) throws Exception {
        RoundRobinTask annotation = Fixtures.class.getDeclaredMethod(fixtureMethod)
                .getAnnotation(RoundRobinTask.class);
        return RoundRobinConfigResolver.resolve(annotation);
    }

    /** @RoundRobinTask 只允许标注在方法上，用方法级 fixture 取注解实例 */
    static class Fixtures {

        @RoundRobinTask(name = "t-desc", frequency = "1m", description = " 清理超时订单 ")
        void described() {
        }

        @RoundRobinTask(name = "t-no-desc", frequency = "1m")
        void noDescription() {
        }

        @RoundRobinTask(name = "t-trim", frequency = "1m", execWatchdog = " true ")
        void whitespaceTrue() {
        }

        @RoundRobinTask(name = "t-false", frequency = "1m", execWatchdog = "FALSE")
        void upperCaseFalse() {
        }

        @RoundRobinTask(name = "t-blank", frequency = "1m", execWatchdog = "   ")
        void blank() {
        }

        @RoundRobinTask(name = "t-invalid", frequency = "1m", execWatchdog = "1")
        void invalid() {
        }
    }

}
