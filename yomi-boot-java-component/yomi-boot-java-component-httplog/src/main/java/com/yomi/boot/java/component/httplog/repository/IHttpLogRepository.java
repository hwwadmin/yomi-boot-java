package com.yomi.boot.java.component.httplog.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;

import java.time.Instant;
import java.util.List;

/**
 * 日志仓储（端口）：sys_http_log 的持久化唯一出入口
 * <p>
 * 查询服务/落库 store/保留期清理一律经本接口访问，**不得越过仓储直接调用 Mapper**；
 * 默认实现 {@link HttpLogRepository}，宿主可自行实现本接口并注册 Bean 顶替。
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

    /** 过期行 id 取样（保留期清理第一阶段；不用 DELETE ... LIMIT 方言） */
    List<Long> selectExpiredIds(String serviceName, Instant before, int limit);

    /** 按 id 删除（保留期清理第二阶段） */
    int deleteByIds(List<Long> ids);

}
