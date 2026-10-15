package com.yomi.boot.java.component.event.exception;

/**
 * 事件组件 {@link com.yomi.boot.java.base.exception.BizException} 的 errorCode
 *
 * @author zwei
 */
public final class EventErrorCode {

    /** eventId / topic 不合法 */
    public static final String EVENT_INVALID = "EVENT_INVALID";
    /** 发件箱唯一键冲突 */
    public static final String DUPLICATE_PUBLISH = "EVENT_DUPLICATE_PUBLISH";
    /** 事件序列化后超过 Kafka 可接受的大小 */
    public static final String EVENT_TOO_LARGE = "EVENT_TOO_LARGE";

    private EventErrorCode() {
    }

}
