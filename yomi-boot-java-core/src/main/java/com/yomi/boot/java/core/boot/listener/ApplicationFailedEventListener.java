package com.yomi.boot.java.core.boot.listener;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;

/**
 * 系统异常关闭事件监听
 *
 * @author zwei
 */
@Slf4j
public class ApplicationFailedEventListener implements ApplicationListener<ApplicationFailedEvent> {

    /** 启动失败时输出原因日志 */
    @Override
    public void onApplicationEvent(@NonNull ApplicationFailedEvent applicationEvent) {
        Throwable throwable = applicationEvent.getException();
        log.error("应用启动失败", throwable);
    }

}
