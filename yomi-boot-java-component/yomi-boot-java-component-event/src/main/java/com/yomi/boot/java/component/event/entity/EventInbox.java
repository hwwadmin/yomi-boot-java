package com.yomi.boot.java.component.event.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 表 t_sys_event_inbox；消费幂等凭证
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("t_sys_event_inbox")
public class EventInbox {

    /** 主键 */
    @TableId
    private Long id;

    /** 消费方服务名 */
    private String serviceName;

    /** 与 service_name 组成唯一键 */
    private String eventId;

    /** Kafka Topic */
    private String topic;

    /** 事件载荷 JSON */
    private String payload;

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
