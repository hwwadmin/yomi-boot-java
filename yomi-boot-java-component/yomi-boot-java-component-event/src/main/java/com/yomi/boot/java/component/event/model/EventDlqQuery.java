package com.yomi.boot.java.component.event.model;

import com.yomi.boot.java.base.model.PageRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/**
 * 发件箱死信查询条件（继承 {@link PageRequest} 分页参数；可按时间范围过滤）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class EventDlqQuery extends PageRequest {

    /** 创建时间起（含，ISO-8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant startTime;

    /** 创建时间止（含，ISO-8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant endTime;

}
