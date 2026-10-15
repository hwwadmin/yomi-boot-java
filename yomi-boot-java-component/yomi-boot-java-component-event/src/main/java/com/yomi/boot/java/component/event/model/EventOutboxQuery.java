package com.yomi.boot.java.component.event.model;

import com.yomi.boot.java.base.model.PageRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/**
 * 发件箱查询条件（继承 {@link PageRequest} 分页参数；可直接作为 Controller 的查询参数对象使用）
 * <p>服务维度由服务层固定为当前服务，不在此暴露。
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class EventOutboxQuery extends PageRequest {

    /** 状态过滤（PENDING/FETCHED/SENT_SUCCESS/SENT_FAIL） */
    private OutboxStatus status;

    /** Topic 精确过滤 */
    private String topic;

    /** 创建时间起（含，ISO-8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant startTime;

    /** 创建时间止（含，ISO-8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant endTime;

}
