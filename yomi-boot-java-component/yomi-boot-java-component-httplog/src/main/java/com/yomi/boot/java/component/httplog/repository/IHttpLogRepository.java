package com.yomi.boot.java.component.httplog.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;

import java.time.Instant;
import java.util.List;

/**
 * 日志仓储（端口）：sys_http_log 的持久化唯一出入口
 * <p>
 * 查询服务/落库 store/保留期清理统一经由本接口访问，直连 Mapper 的只有仓储实现；
 * 默认实现 {@link HttpLogRepository}，宿主自行实现本接口并注册 Bean 后以宿主的实现为准。
 *
 * @author zwei
 */
public interface IHttpLogRepository {

    /**
     * 概要分页（不含请求/响应头与体大字段；请求时间倒序）
     *
     * @param serviceFilter 服务过滤（null=不限服务，由调用方决定业务规则）
     */
    Page<HttpLogEntity> pageSummary(HttpLogQuery condition, String serviceFilter);

    /** 按主键查询（全量字段）；不存在返回 null */
    HttpLogEntity findById(Long id);

    /** 批量插入（JDBC batch，跨库安全） */
    void insertBatch(List<HttpLogEntity> entities);

    /** 单条插入（完整性冲突逐条隔离场景） */
    void insert(HttpLogEntity entity);

    /** 过期行 id 取样（保留期清理第一阶段；删除按 id 逐批执行，SQL 保持跨库通用） */
    List<Long> selectExpiredIds(String serviceName, Instant before, int limit);

    /** 按 id 删除（保留期清理第二阶段） */
    int deleteByIds(List<Long> ids);

}
