package com.yomi.boot.java.component.event.consume;

import com.yomi.boot.java.base.exception.UtilsException;
import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.event.model.WireEvent;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 解析 Kafka 消息为 {@link WireEvent}；JSON 语法错误或校验失败一律返回 empty，不抛异常
 *
 * @author zwei
 */
public final class WireEventParser {

    private WireEventParser() {
    }

    /** 解析线协议消息 */
    public static Optional<WireEvent> parse(byte[] body, boolean requireEventId) {
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        return parse(new String(body, StandardCharsets.UTF_8), requireEventId);
    }

    /** 解析线协议消息 */
    public static Optional<WireEvent> parse(String body, boolean requireEventId) {
        if (StringUtils.isBlank(body)) {
            return Optional.empty();
        }
        WireEvent evt;
        try {
            evt = JsonUtils.parse(body, WireEvent.class);
        } catch (UtilsException e) {
            // 语法非法的消息必须走"丢弃"而不是抛给监听器：抛异常会让同一条毒消息被反复重投，
            // 调用方（EventInboxGuard / EventUtils.onMessage）的 DROP 分支也就永远不可达
            return Optional.empty();
        }
        if (evt == null || StringUtils.isBlank(evt.getTopic())) {
            return Optional.empty();
        }
        if (requireEventId && StringUtils.isBlank(evt.getEventId())) {
            return Optional.empty();
        }
        return Optional.of(evt);
    }

}
