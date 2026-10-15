package com.yomi.boot.java.component.captcha.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.component.captcha.exception.CaptchaErrorCode;
import com.yomi.boot.java.component.captcha.exception.CaptchaException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常响应：响应体只给通用提示，原因链必须落进日志（否则存储/渲染故障的 cause 无从查起）
 */
class CaptchaExceptionAdviceTest {

    @Test
    void causeChainIsLoggedButNotExposed() {
        CaptchaException failure = CaptchaException.of(CaptchaErrorCode.VERIFY_FAILED, "验证码校验失败",
                new IllegalStateException("redis down"));

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(CaptchaExceptionAdvice.class);
        logger.addAppender(appender);
        Response<Void> response;
        try {
            response = new CaptchaExceptionAdvice().captchaException(failure);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.size(), "异常必须打日志");
        ILoggingEvent event = appender.list.get(0);
        assertTrue(event.getFormattedMessage().contains(CaptchaErrorCode.VERIFY_FAILED.getCode()),
                event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "日志必须带异常对象，否则 cause 链（如 Redis 故障）无处可见");
        assertNotNull(event.getThrowableProxy().getCause());
        assertEquals("redis down", event.getThrowableProxy().getCause().getMessage());

        assertEquals(500, response.getCode());
        assertEquals(CaptchaErrorCode.VERIFY_FAILED.getCode(), response.getErrorCode());
        assertFalse(String.valueOf(response.getDebugMessage()).contains("redis down"), "响应体不暴露内部细节");
    }

}
