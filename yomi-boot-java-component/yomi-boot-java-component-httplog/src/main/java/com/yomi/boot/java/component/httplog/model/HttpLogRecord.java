package com.yomi.boot.java.component.httplog.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 一条 HTTP 请求日志（采集完成后的快照）
 * <p>
 * 请求头/响应头/query 在采集线程已脱敏；请求体/响应体是否已脱敏取决于
 * {@code yomi.httplog.desensitize.in-request-thread}（默认否，由后台线程在落库/发送前处理）。
 *
 * @author zwei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpLogRecord {

    /** 雪花ID（采集时生成，落库主键；重复消费按主键收敛） */
    private long id;

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

    /** 用户ID（HttpLogContextEnricher 填充，可空） */
    private Long userId;

    /** 请求头 JSON（全量，命中键已脱敏） */
    private String requestHeaders;

    /** 请求体（截断后；multipart/二进制等跳过时为 null） */
    private String requestBody;

    /** 请求体是否被截断 */
    private boolean requestBodyTruncated;

    /** 响应头 JSON（全量，命中键已脱敏） */
    private String responseHeaders;

    /** 响应体（截断后；二进制/流式等跳过时为 null） */
    private String responseBody;

    /** 响应体是否被截断 */
    private boolean responseBodyTruncated;

    /** 错误信息（HttpLogContextEnricher 填充，可空） */
    private String errorMsg;

    /** 是否异步请求（响应体与最终状态可能不完整） */
    private boolean asyncStarted;

    /** 请求 Content-Type（仅用于 body 脱敏判断与传输，不落库） */
    private String requestContentType;

    /** 响应 Content-Type（仅用于 body 脱敏判断与传输，不落库） */
    private String responseContentType;

}
