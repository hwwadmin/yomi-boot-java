package com.yomi.boot.java.component.rbac.exception;

import com.yomi.boot.java.base.exception.BizException;

/**
 * RBAC 管理面业务异常
 *
 * @author zwei
 */
public final class RbacBiz {

    private RbacBiz() {
    }

    /** 按错误码构造管理面业务异常 */
    public static BizException of(RbacErrorCode code) {
        return BizException.exception(code.getCode(), code.getMessage());
    }

    /** 按错误码与自定义详情构造管理面业务异常 */
    public static BizException of(RbacErrorCode code, String detail) {
        return BizException.exception(code.getCode(), detail);
    }

}
