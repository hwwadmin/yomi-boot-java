package com.yomi.boot.java.component.event.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 发件箱死信查询视图（含关联事件的 eventId，便于排障定位）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
public class EventDlqModel {

    /** 主键ID（雪花） */
    private Long id;

    /** 关联发件箱主键 */
    private Long eventOutboxId;

    /** 关联事件的 eventId（由服务层批量补全） */
    private String eventId;

    /** 最近失败信息 */
    private String errorMsg;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最近一次重试时间 */
    private Instant retryLastTime;

    /** 最早可再次重试时间 */
    private Instant retryAfterTime;

    /** 创建时间 */
    private Instant createTime;

}
