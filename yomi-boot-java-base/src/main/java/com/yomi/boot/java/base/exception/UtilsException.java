package com.yomi.boot.java.base.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.StrUtils;

/**
 * 工具类异常
 *
 * @author zwei
 */
public class UtilsException extends AbstractRuntimeException {

    private UtilsException(String detailMessage) {
        super(StatusCode.utilsError.getCode(), detailMessage);
    }

    private UtilsException(String detailMessage, Throwable e) {
        super(StatusCode.utilsError.getCode(), detailMessage, e);
    }

    /** 创建工具类异常 */
    public static UtilsException exception(String detailMessage) {
        return new UtilsException(detailMessage);
    }

    /** 按模板格式化消息后创建工具类异常 */
    public static UtilsException exception(String detailMessage, Object... params) {
        return new UtilsException(StrUtils.format(detailMessage, params));
    }

    /** 包装原因异常并创建工具类异常 */
    public static UtilsException exception(Throwable e, String detailMessage) {
        return new UtilsException(detailMessage, e);
    }

    /** 包装原因异常并按模板格式化消息后创建工具类异常 */
    public static UtilsException exception(Throwable e, String detailMessage, Object... params) {
        return new UtilsException(StrUtils.format(detailMessage, params), e);
    }

}
