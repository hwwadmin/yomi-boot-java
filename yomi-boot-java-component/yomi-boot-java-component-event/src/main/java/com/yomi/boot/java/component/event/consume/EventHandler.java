package com.yomi.boot.java.component.event.consume;

import com.yomi.boot.java.component.event.model.WireEvent;

/**
 * 事件业务处理回调；由 {@link EventInboxGuard} 调用：{@code onMessage} 路径在 inbox 事务内执行，
 * {@code onMessageNormal} 路径不在事务内
 *
 * @author zwei
 */
@FunctionalInterface
public interface EventHandler {

    void handle(WireEvent event) throws Exception;

}
