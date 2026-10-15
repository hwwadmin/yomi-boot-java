package com.yomi.boot.java.component.rbac.cache;

import lombok.Data;


/**
 * Redis 投影当前版本指针
 *
 * @author zwei
 */
@Data
public class RbacCachePointer {

    /** 当前投影版本号 */
    private String v;

    /** 行数校验（六表合计） */
    private int n;

}
