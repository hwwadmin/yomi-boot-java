package com.yomi.boot.java.component.event.convert;

import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.EventDlqModel;
import com.yomi.boot.java.component.event.model.EventOutboxModel;

/**
 * 事件实体 → 查询视图转换（模型类只保留数据）
 *
 * @author zwei
 */
public final class EventModelConverter {

    private EventModelConverter() {
    }

    /** 发件箱实体 → 发件箱视图（概要瘦查询未选中的列自然为 null） */
    public static EventOutboxModel toOutboxModel(EventOutbox entity) {
        EventOutboxModel model = new EventOutboxModel();
        model.setId(entity.getId());
        model.setEventId(entity.getEventId());
        model.setTopic(entity.getTopic());
        model.setStatus(entity.getStatus());
        model.setFetchTime(entity.getFetchTime());
        model.setSentTime(entity.getSentTime());
        model.setCreateTime(entity.getCreateTime());
        model.setPayload(entity.getPayload());
        return model;
    }

    /** 死信实体 → 死信视图（关联 eventId 由服务层批量补全） */
    public static EventDlqModel toDlqModel(EventOutboxDlq entity) {
        EventDlqModel model = new EventDlqModel();
        model.setId(entity.getId());
        model.setEventOutboxId(entity.getEventOutboxId());
        model.setErrorMsg(entity.getErrorMsg());
        model.setRetryCount(entity.getRetryCount());
        model.setRetryLastTime(entity.getRetryLastTime());
        model.setRetryAfterTime(entity.getRetryAfterTime());
        model.setCreateTime(entity.getCreateTime());
        return model;
    }

}
