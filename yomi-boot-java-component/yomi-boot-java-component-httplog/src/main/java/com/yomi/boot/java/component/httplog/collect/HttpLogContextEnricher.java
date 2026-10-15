package com.yomi.boot.java.component.httplog.collect;

import com.yomi.boot.java.component.httplog.model.HttpLogRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 采集内容扩展点：填充 userId、errorMsg 等业务字段（宿主按需注册 Bean，可多个）
 * <p>实现须自行 try-catch：异常不外溢，组件按"扩展失败"计数。
 *
 * @author zwei
 */
@FunctionalInterface
public interface HttpLogContextEnricher {

    /**
     * @param record 已完成基础组装的记录（可变），仅填充空字段，勿覆盖组件已填字段
     */
    void enrich(HttpServletRequest request, HttpServletResponse response, HttpLogRecord record);

}
