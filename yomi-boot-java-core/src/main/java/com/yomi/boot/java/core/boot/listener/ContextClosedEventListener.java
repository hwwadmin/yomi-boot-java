package com.yomi.boot.java.core.boot.listener;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 系统关闭事件监听
 *
 * @author zwei
 */
@Slf4j
public class ContextClosedEventListener implements ApplicationListener<ContextClosedEvent> {

    /** 容器关闭时输出提示日志 */
    @Override
    public void onApplicationEvent(@NonNull ContextClosedEvent applicationEvent) {
        log.info("系统关闭，优雅清场");
    }

}
