package com.yomi.boot.java.component.httplog.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 表 sys_http_log；append-only，delete_time/delete_by 恒为空（保留期走物理删除）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("sys_http_log")
public class HttpLogEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 归属业务系统名称 */
    private String serviceName;

    /** 链路跟踪ID */
    private String traceId;

    /** 请求进入时间 */
    private Instant requestTime;

    /** 处理耗时（毫秒） */
    private Integer durationMs;

    /** HTTP 方法 */
    private String method;

    /** 请求路径（不含查询串） */
    private String uri;

    /** URL 参数（脱敏后） */
    private String queryString;

    /** 响应状态码 */
    private Integer status;

    /** 客户端IP */
    private String clientIp;

    /** 用户ID（HttpLogContextEnricher 填充） */
    private Long userId;

    /** 请求头 JSON（全量，命中键已脱敏） */
    private String requestHeaders;

    /** 请求体（截断，脱敏后） */
    private String requestBody;

    /** 请求体是否截断 */
    private Boolean requestBodyTruncated;

    /** 响应头 JSON（全量，命中键已脱敏） */
    private String responseHeaders;

    /** 响应体（截断，脱敏后） */
    private String responseBody;

    /** 响应体是否截断 */
    private Boolean responseBodyTruncated;

    /** 错误信息（HttpLogContextEnricher 填充） */
    private String errorMsg;

    /** 是否异步请求 */
    private Boolean asyncStarted;

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
