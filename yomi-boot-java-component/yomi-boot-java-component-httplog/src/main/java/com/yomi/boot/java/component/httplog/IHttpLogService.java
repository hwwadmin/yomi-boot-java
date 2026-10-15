package com.yomi.boot.java.component.httplog;

import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.httplog.model.HttpLogModel;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;

/**
 * HTTP 请求日志查询服务（业务系统接入用）
 * <p>
 * 由自动装配提供默认实现（{@code service.HttpLogService}），宿主可自行实现本接口并注册为 Bean 顶替。
 * <p>契约：
 * <ul>
 *     <li>返回的是**入库时已脱敏**的内容（脱敏范围由 {@code yomi.httplog.desensitize.key-types} 决定）；</li>
 *     <li>{@link #page} 返回概要字段（不含请求/响应头与体），{@link #get} 返回全量字段；</li>
 *     <li>可见数据受保留期清理约束（{@code yomi.httplog.retention}）；</li>
 *     <li>服务可见范围：{@link #page} 默认只查当前服务（聚合多业务共库时避免列表串查，
 *     {@link HttpLogQuery#isAllServices()} 可放开）；{@link #get} 按 id 不受服务限制（聚合表统一查看场景）。</li>
 * </ul>
 *
 * @author zwei
 */
public interface IHttpLogService {

    /**
     * 按条件分页查询（请求时间倒序）
     *
     * @param query 条件（可传 null=默认条件查当前服务首页）
     */
    PageResponse<HttpLogModel> page(HttpLogQuery query);

    /**
     * 按主键查询单条（含请求/响应头与体）
     * <p>**不做服务过滤**：多业务日志聚合到一张表、后台按 id 统一查看是主场景（{@link #page} 的默认服务限定不适用于按 id 定位）
     *
     * @return 不存在时返回 null
     */
    HttpLogModel get(Long id);

}
