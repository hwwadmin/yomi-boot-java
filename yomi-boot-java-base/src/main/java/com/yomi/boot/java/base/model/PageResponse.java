package com.yomi.boot.java.base.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.Lists;
import lombok.Getter;

import java.util.List;

/**
 * 分页视图
 *
 * @param <T> 视图类型
 * @author zwei
 */
@Getter
@JsonPropertyOrder({"code", "errorCode", "debugMessage", "timestamp", "data", "total"})
public class PageResponse<T> extends Response<List<T>> {

    /**
     * 总记录数
     */
    protected long total;

    protected PageResponse(List<T> result) {
        super();
        this.code = StatusCode.succeed.getCode();
        this.data = result;
    }

    /** 空列表且 total 为 0 的分页响应 */
    public static <T> PageResponse<T> empty() {
        return page(Lists.of(), 0);
    }

    /** 构造分页响应；{@code list} 为 null 时按空列表处理 */
    public static <T> PageResponse<T> page(List<T> list, long total) {
        PageResponse<T> vo = new PageResponse<>(list == null ? Lists.of() : list);
        vo.total = total;
        return vo;
    }

}
