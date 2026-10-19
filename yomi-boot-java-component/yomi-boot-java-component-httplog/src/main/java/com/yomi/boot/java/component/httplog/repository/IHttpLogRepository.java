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

    /**
     * 保留期清理：限额物理删除该服务 {@code create_time} 早于阈值的行
     *
     * @param limit 单条 DELETE 的行数上限（原样下传，实现不得夹取/取小）
     * @return 实际删除行数
     */
    int deleteExpiredLimited(String serviceName, Instant createTimeBefore, int limit);

    /** 该服务最旧一行的 {@code create_time}（表空返回 null）；落后度量用 */
    Instant findOldestCreateTime(String serviceName);

}
