package com.yomi.boot.java.base.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import com.yomi.boot.java.base.utils.StrUtils;

/**
 * 断言异常
 *
 * @author zwei
 */
public class AssertException extends AbstractRuntimeException {

    private AssertException(String detailMessage) {
        super(StatusCode.assertError.getCode(), detailMessage);
    }

    private AssertException(String detailMessage, Throwable e) {
        super(StatusCode.assertError.getCode(), detailMessage, e);
    }

    /** 创建断言异常 */
    public static AssertException exception(String detailMessage) {
        return new AssertException(detailMessage);
    }

    /** 按模板格式化消息后创建断言异常 */
    public static AssertException exception(String detailMessage, Object... params) {
        return new AssertException(StrUtils.format(detailMessage, params));
    }

    /** 包装原因异常并创建断言异常 */
    public static AssertException exception(Throwable e, String detailMessage) {
        return new AssertException(detailMessage, e);
    }

    /** 包装原因异常并按模板格式化消息后创建断言异常 */
    public static AssertException exception(Throwable e, String detailMessage, Object... params) {
        return new AssertException(StrUtils.format(detailMessage, params), e);
    }

}
