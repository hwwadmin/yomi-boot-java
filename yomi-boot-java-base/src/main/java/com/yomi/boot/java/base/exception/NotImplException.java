package com.yomi.boot.java.base.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.StrUtils;

/**
 * 方法未实现异常
 *
 * @author zwei
 */
public class NotImplException extends AbstractRuntimeException {

    /** 默认提示文案 */
    private static final String DEFAULT_MESSAGE = "The function has not been implemented.";

    private NotImplException(String detailMessage) {
        super(StatusCode.notImplemented.getCode(), detailMessage);
    }

    /** 使用默认文案创建未实现异常 */
    public static NotImplException exception() {
        return new NotImplException(DEFAULT_MESSAGE);
    }

    /** 创建未实现异常 */
    public static NotImplException exception(String detailMessage) {
        return new NotImplException(detailMessage);
    }

    /** 按模板格式化消息后创建未实现异常 */
    public static NotImplException exception(String detailMessage, Object... params) {
        return new NotImplException(StrUtils.format(detailMessage, params));
    }

}
