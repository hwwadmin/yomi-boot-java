package com.yomi.boot.java.component.httplog.convert;

import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogModel;

/**
 * 日志实体 → 查询视图转换（模型类只保留数据）
 *
 * @author zwei
 */
public final class HttpLogModelConverter {

    private HttpLogModelConverter() {
    }

    /** 日志实体 → 日志视图（page 的瘦查询未选中的列自然为 null） */
    public static HttpLogModel toHttpLogModel(HttpLogEntity entity) {
        HttpLogModel model = new HttpLogModel();
        model.setId(entity.getId());
        model.setServiceName(entity.getServiceName());
        model.setTraceId(entity.getTraceId());
        model.setRequestTime(entity.getRequestTime());
        model.setDurationMs(entity.getDurationMs());
        model.setMethod(entity.getMethod());
        model.setUri(entity.getUri());
        model.setQueryString(entity.getQueryString());
        model.setStatus(entity.getStatus());
        model.setClientIp(entity.getClientIp());
        model.setUserId(entity.getUserId());
        model.setRequestHeaders(entity.getRequestHeaders());
        model.setRequestBody(entity.getRequestBody());
        model.setRequestBodyTruncated(entity.getRequestBodyTruncated());
        model.setResponseHeaders(entity.getResponseHeaders());
        model.setResponseBody(entity.getResponseBody());
        model.setResponseBodyTruncated(entity.getResponseBodyTruncated());
        model.setErrorMsg(entity.getErrorMsg());
        model.setAsyncStarted(entity.getAsyncStarted());
        return model;
    }

}
