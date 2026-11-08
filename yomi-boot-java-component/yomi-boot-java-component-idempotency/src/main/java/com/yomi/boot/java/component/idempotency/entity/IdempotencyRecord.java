package com.yomi.boot.java.component.idempotency.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 表 sys_idempotency_record；建表脚本在仓库根目录 sql/mysql|pgsql/sys_idempotency_record.sql
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("sys_idempotency_record")
public class IdempotencyRecord {

    /** 主键 */
    @TableId
    private Long id;

    /** 幂等命名空间，与 idempotency_key 组成唯一键 */
    private String namespace;

    /** 幂等键 */
    private String idempotencyKey;

    /** 请求指纹摘要（SHA-256 hex），可选 */
    private String requestHash;

    /** 状态：1 处理中，2 已完成 */
    private Integer status;

    /** 业务返回类型的全限定名 */
    private String resultType;

    /** 业务返回 JSON */
    private String resultPayload;

    /** 记录过期时间，供清理任务使用 */
    private Instant expireTime;

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
