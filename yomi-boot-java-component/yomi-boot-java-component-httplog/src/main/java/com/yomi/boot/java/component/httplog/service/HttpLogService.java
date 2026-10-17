package com.yomi.boot.java.component.httplog.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.httplog.IHttpLogService;
import com.yomi.boot.java.component.httplog.convert.HttpLogModelConverter;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogModel;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import org.apache.commons.lang3.StringUtils;

/**
 * 查询服务默认实现（{@link IHttpLogService}）：业务规则（默认服务过滤）在服务层，持久化细节在仓储层
 * <p>宿主自行实现 {@link IHttpLogService} 并注册 Bean 后以宿主的实现为准。
 *
 * @author zwei
 */
public class HttpLogService implements IHttpLogService {

    /** 数据访问 */
    private final IHttpLogRepository repository;

    /** 多服务隔离用的键前缀 */
    private final HttpLogKeys keys;

    public HttpLogService(IHttpLogRepository repository, HttpLogKeys keys) {
        this.repository = repository;
        this.keys = keys;
    }

    /** 管理端分页查询 */
    @Override
    public PageResponse<HttpLogModel> page(HttpLogQuery query) {
        HttpLogQuery condition = query == null ? new HttpLogQuery() : query;
        Page<HttpLogEntity> result = repository.pageSummary(condition, resolveServiceFilter(condition));
        return PageResponse.page(result.getRecords().stream().map(HttpLogModelConverter::toHttpLogModel).toList(),
                result.getTotal());
    }

    /** 按 id 查单条详情 */
    @Override
    public HttpLogModel get(Long id) {
        if (id == null) {
            return null;
        }
        HttpLogEntity entity = repository.findById(id);
        return entity == null ? null : HttpLogModelConverter.toHttpLogModel(entity);
    }

    /** 服务维度过滤：allServices 放开；显式 serviceName 优先；默认限当前服务 */
    private String resolveServiceFilter(HttpLogQuery condition) {
        if (condition.isAllServices()) {
            return null;
        }
        return StringUtils.isNotBlank(condition.getServiceName())
                ? condition.getServiceName().trim() : keys.getServiceName();
    }

}
