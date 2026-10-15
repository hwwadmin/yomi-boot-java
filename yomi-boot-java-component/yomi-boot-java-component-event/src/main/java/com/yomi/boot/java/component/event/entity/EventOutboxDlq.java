package com.yomi.boot.java.component.event.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 表 sys_event_outbox_dlq；发件箱发送失败后的重试队列
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_event_outbox_dlq")
public class EventOutboxDlq extends BaseEntity {

    /** 归属服务名 */
    private String serviceName;

    /** 关联发件箱主键，唯一 */
    private Long eventOutboxId;

    /** 最近一次失败原因 */
    private String errorMsg;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最近一次重试时间 */
    private Instant retryLastTime;

    /** 下次允许扫描重试的时间（退避窗口） */
    private Instant retryAfterTime;

}
