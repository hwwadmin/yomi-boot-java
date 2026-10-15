package com.yomi.boot.java.component.httplog.model;

import com.yomi.boot.java.base.model.PageRequest;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

/**
 * 日志查询条件（继承 {@link PageRequest} 的分页参数；可直接作为 Controller 的查询参数对象使用）
 *
 * @author zwei
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class HttpLogQuery extends PageRequest {

    /** 服务名（为空=仅当前服务） */
    private String serviceName;

    /** true=不限服务（多服务共库的管理查询时使用） */
    private boolean allServices;

    /** 链路跟踪ID */
    private String traceId;

    /** 用户ID */
    private Long userId;

    /** HTTP 方法（不区分大小写） */
    private String method;

    /** 请求路径模糊匹配（LIKE %x%；大表请配合时间范围使用） */
    private String uriLike;

    /** 响应状态码精确匹配 */
    private Integer status;

    /** 响应状态码下限（如 500 只看错误响应） */
    private Integer minStatus;

    /** 请求时间起（含，ISO-8601，如 2026-10-13T00:00:00Z） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant startTime;

    /** 请求时间止（含，ISO-8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant endTime;

}
