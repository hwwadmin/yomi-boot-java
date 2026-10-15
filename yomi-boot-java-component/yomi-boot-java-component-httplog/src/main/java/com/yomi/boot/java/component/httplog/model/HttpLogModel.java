package com.yomi.boot.java.component.httplog.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 日志查询视图（{@code page} 为概要、{@code get} 为全量；内容为入库时已脱敏值）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
public class HttpLogModel {

    /** 主键ID（雪花） */
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

    /** 用户ID */
    private Long userId;

    /** 请求头 JSON（仅 get） */
    private String requestHeaders;

    /** 请求体（仅 get；截断后） */
    private String requestBody;

    /** 请求体是否被截断 */
    private Boolean requestBodyTruncated;

    /** 响应头 JSON（仅 get） */
    private String responseHeaders;

    /** 响应体（仅 get；截断后） */
    private String responseBody;

    /** 响应体是否被截断 */
    private Boolean responseBodyTruncated;

    /** 错误信息 */
    private String errorMsg;

    /** 是否异步请求 */
    private Boolean asyncStarted;

}
