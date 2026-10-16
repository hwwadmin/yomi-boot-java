package com.yomi.boot.java.component.httplog.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;
import com.yomi.boot.java.component.httplog.repository.mapper.HttpLogMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 仓储默认实现（{@link IHttpLogRepository}）：列选择、条件组装、批量会话、两阶段删除等持久化细节收口
 * <p>参数化构造器（零拼接 SQL），批量插入走 JDBC batch（跨库安全）。
 *
 * @author zwei
 */
public class HttpLogRepository implements IHttpLogRepository {

    /** HTTP 日志 MyBatis Mapper */
    private final HttpLogMapper mapper;

    /** 批量插入用的 SqlSession 工厂 */
    private final SqlSessionFactory sqlSessionFactory;

    public HttpLogRepository(HttpLogMapper mapper, SqlSessionFactory sqlSessionFactory) {
        this.mapper = mapper;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /** 分页查询摘要列表 */
    @Override
    public Page<HttpLogEntity> pageSummary(HttpLogQuery condition, String serviceFilter) {
        Page<HttpLogEntity> page = new Page<>(
                Math.max(condition.getPageNo(), 1),
                Math.min(Math.max(condition.getPageSize(), 1), PageRequest.MAX_PAGE_SIZE));
        return mapper.selectPage(page, summaryWrapper(condition, serviceFilter));
    }

    /** 按主键查详情 */
    @Override
    public HttpLogEntity findById(Long id) {
        return mapper.selectById(id);
    }

    /** 批量插入（独立 SqlSession） */
    @Override
    public void insertBatch(List<HttpLogEntity> entities) {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            HttpLogMapper batchMapper = session.getMapper(HttpLogMapper.class);
            for (HttpLogEntity entity : entities) {
                batchMapper.insert(entity);
            }
            session.commit();
        }
    }

    /** 单条插入 */
    @Override
    public void insert(HttpLogEntity entity) {
        mapper.insert(entity);
    }

    /** 查询超过保留期的 id 列表（只要一批 id：关掉 count，否则每批都多扫一遍整个过期区间） */
    @Override
    public List<Long> selectExpiredIds(String serviceName, Instant before, int limit) {
        Page<HttpLogEntity> page = new Page<>(1, Math.min(Math.max(limit, 1), PageRequest.MAX_PAGE_SIZE));
        page.setSearchCount(false);
        return mapper.selectPage(page, new LambdaQueryWrapper<HttpLogEntity>()
                        .select(HttpLogEntity::getId)
                        .eq(HttpLogEntity::getServiceName, serviceName)
                        .lt(HttpLogEntity::getCreateTime, before)
                        .orderByAsc(HttpLogEntity::getCreateTime))
                .getRecords().stream().map(HttpLogEntity::getId).toList();
    }

    /** 按 id 批量物理删除 */
    @Override
    public int deleteByIds(List<Long> ids) {
        return mapper.delete(new LambdaQueryWrapper<HttpLogEntity>().in(HttpLogEntity::getId, ids));
    }

    /** 概要列 + 条件组装（列名等持久化细节收在仓储内） */
    private LambdaQueryWrapper<HttpLogEntity> summaryWrapper(HttpLogQuery condition, String serviceFilter) {
        // 条件为 false 时 value 参数仍会被求值，空值处理必须先于构造调用（防 NPE）
        String traceId = StringUtils.trimToNull(condition.getTraceId());
        String method = StringUtils.isNotBlank(condition.getMethod())
                ? condition.getMethod().trim().toUpperCase(Locale.ROOT) : null;
        return new LambdaQueryWrapper<HttpLogEntity>()
                .select(HttpLogEntity::getId, HttpLogEntity::getServiceName, HttpLogEntity::getTraceId,
                        HttpLogEntity::getRequestTime, HttpLogEntity::getDurationMs, HttpLogEntity::getMethod,
                        HttpLogEntity::getUri, HttpLogEntity::getQueryString, HttpLogEntity::getStatus,
                        HttpLogEntity::getClientIp, HttpLogEntity::getUserId, HttpLogEntity::getErrorMsg,
                        HttpLogEntity::getAsyncStarted)
                .eq(serviceFilter != null, HttpLogEntity::getServiceName, serviceFilter)
                .eq(traceId != null, HttpLogEntity::getTraceId, traceId)
                .eq(condition.getUserId() != null, HttpLogEntity::getUserId, condition.getUserId())
                .eq(method != null, HttpLogEntity::getMethod, method)
                .like(StringUtils.isNotBlank(condition.getUriLike()), HttpLogEntity::getUri,
                        condition.getUriLike())
                .eq(condition.getStatus() != null, HttpLogEntity::getStatus, condition.getStatus())
                .ge(condition.getMinStatus() != null, HttpLogEntity::getStatus, condition.getMinStatus())
                .ge(condition.getStartTime() != null, HttpLogEntity::getCreateTime, condition.getStartTime())
                .le(condition.getEndTime() != null, HttpLogEntity::getCreateTime, condition.getEndTime())
                .orderByDesc(HttpLogEntity::getCreateTime)
                .orderByDesc(HttpLogEntity::getId);
    }

}
