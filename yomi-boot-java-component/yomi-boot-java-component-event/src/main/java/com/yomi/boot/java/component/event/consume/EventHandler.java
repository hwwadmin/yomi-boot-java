package com.yomi.boot.java.component.event.consume;

import com.yomi.boot.java.component.event.model.WireEvent;

/**
 * 事件业务处理回调；在 {@link EventInboxGuard} 中于 inbox 事务内调用
 *
 * @author zwei
 */
@FunctionalInterface
public interface EventHandler {

    void handle(WireEvent event) throws Exception;

}
