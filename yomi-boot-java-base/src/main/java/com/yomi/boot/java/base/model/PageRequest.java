package com.yomi.boot.java.base.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页请求参数
 *
 * @author zwei
 */
@Data
public class PageRequest {

    /** 默认页码 */
    public static final int DEFAULT_PAGE_NO = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 单页最大条数 */
    public static final int MAX_PAGE_SIZE = 500;

    /**
     * 页码，从 1 开始
     */
    @Min(1)
    private int pageNo = DEFAULT_PAGE_NO;

    /**
     * 每页条数
     */
    @Min(1)
    @Max(MAX_PAGE_SIZE)
    private int pageSize = DEFAULT_PAGE_SIZE;

}
