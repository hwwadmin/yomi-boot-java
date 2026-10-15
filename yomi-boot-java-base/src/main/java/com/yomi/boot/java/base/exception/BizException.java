package com.yomi.boot.java.base.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.StrUtils;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 业务异常
 *
 * @author zwei
 */
@Getter
public class BizException extends AbstractRuntimeException {

    /** 未指定业务错误码时的占位码 */
    private static final String UNKNOWN_BIZ_CODE = "UNKNOWN_BIZ_CODE";

    /** 业务错误码（稳定字符串，供前端 i18n） */
    private final String errorCode;

    private BizException(String errorCode, String detailMessage) {
        super(StatusCode.bizError.getCode(), detailMessage);
        this.errorCode = StringUtils.isNotBlank(errorCode) ? errorCode : UNKNOWN_BIZ_CODE;
    }

    private BizException(String errorCode, String detailMessage, Throwable e) {
        super(StatusCode.bizError.getCode(), detailMessage, e);
        this.errorCode = StringUtils.isNotBlank(errorCode) ? errorCode : UNKNOWN_BIZ_CODE;
    }

    /** 仅消息创建业务异常（错误码为 {@link #UNKNOWN_BIZ_CODE}） */
    public static BizException exception(String detailMessage) {
        return new BizException(null, detailMessage);
    }

    /** 按错误码与消息创建业务异常 */
    public static BizException exception(String errorCode, String detailMessage) {
        return new BizException(errorCode, detailMessage);
    }

    /** 按错误码与格式化消息创建业务异常 */
    public static BizException exception(String errorCode, String detailMessage, Object... params) {
        return new BizException(errorCode, StrUtils.format(detailMessage, params));
    }

    /** 包装原因异常并创建业务异常（错误码为 {@link #UNKNOWN_BIZ_CODE}） */
    public static BizException exception(Throwable e, String detailMessage) {
        return new BizException(null, detailMessage, e);
    }

    /** 包装原因异常并按错误码与消息创建业务异常 */
    public static BizException exception(Throwable e, String errorCode, String detailMessage) {
        return new BizException(errorCode, detailMessage, e);
    }

    /** 包装原因异常并按错误码与格式化消息创建业务异常 */
    public static BizException exception(Throwable e, String errorCode, String detailMessage, Object... params) {
        return new BizException(errorCode, StrUtils.format(detailMessage, params), e);
    }

}
