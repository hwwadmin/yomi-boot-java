package com.yomi.boot.java.component.event.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yomi.boot.java.component.ds.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 表 sys_event_inbox；消费幂等凭证
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("sys_event_inbox")
public class EventInbox extends BaseEntity {

    /** 消费方服务名 */
    private String serviceName;

    /** 与 service_name 组成唯一键 */
    private String eventId;

    /** Kafka Topic */
    private String topic;

    /** 事件载荷 JSON */
    private String payload;

}
