package com.yomi.boot.java.component.event.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
@ToString
@Accessors(chain = true)
@TableName("sys_event_outbox_dlq")
public class EventOutboxDlq {

    /** 主键 */
    @TableId
    private Long id;

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

    /** 创建时间 */
    private Instant createTime;

    /** 创建人ID */
    private Long createBy;

    /** 更新时间 */
    private Instant updateTime;

    /** 更新人ID */
    private Long updateBy;

    /** 删除时间 */
    private Instant deleteTime;

    /** 删除人ID */
    private Long deleteBy;

}
