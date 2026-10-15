package com.yomi.boot.java.component.rbac.exception;

import com.yomi.boot.java.base.exception.AbstractRuntimeException;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * RBAC 鉴权失败（403）
 *
 * @author zwei
 */
@Getter
public class RbacAuthException extends AbstractRuntimeException {

    /** 鉴权失败错误分类 */
    private final RbacErrorCode errorCode;

    private RbacAuthException(RbacErrorCode errorCode, String detailMessage, Throwable cause) {
        super(errorCode.getResponseCode(), StringUtils.defaultIfBlank(detailMessage, errorCode.getMessage()), cause);
        this.errorCode = errorCode;
    }

    /** 鉴权失败（使用错误码默认文案） */
    public static RbacAuthException of(RbacErrorCode errorCode) {
        return new RbacAuthException(errorCode, null, null);
    }

    /** 鉴权失败（自定义详情，可附带根因） */
    public static RbacAuthException of(RbacErrorCode errorCode, String detailMessage, Throwable cause) {
        return new RbacAuthException(errorCode, detailMessage, cause);
    }

}
