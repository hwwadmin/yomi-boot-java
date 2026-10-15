package com.yomi.boot.java.component.event.exception;

import com.yomi.boot.java.base.exception.BizException;

/**
 * 事件相关业务异常
 *
 * @author zwei
 */
public final class EventBizException {

    private EventBizException() {
    }

    /** 事件参数或状态非法 */
    public static BizException invalid(String detail) {
        return BizException.exception(EventErrorCode.EVENT_INVALID, detail);
    }

    /** 同一 serviceName + eventId 重复发布 */
    public static BizException duplicatePublish(String serviceName, String eventId) {
        return BizException.exception(EventErrorCode.DUPLICATE_PUBLISH,
                "重复发布: serviceName=" + serviceName + ", eventId=" + eventId);
    }

    /** 序列化后体积超过 {@code yomi.event.max-payload-bytes} */
    public static BizException tooLarge(String eventId, int actualBytes, int maxBytes) {
        return BizException.exception(EventErrorCode.EVENT_TOO_LARGE,
                "事件序列化后 {} 字节，超过上限 {} 字节（yomi.event.max-payload-bytes），"
                        + "该事件无法被 Kafka 投递: eventId={}", actualBytes, maxBytes, eventId);
    }

}
