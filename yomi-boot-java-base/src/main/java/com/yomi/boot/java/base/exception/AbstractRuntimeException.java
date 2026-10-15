package com.yomi.boot.java.base.exception;

import lombok.Getter;

/**
 * 基础运行期异常
 *
 * @author zwei
 */
@Getter
public abstract class AbstractRuntimeException extends RuntimeException {

    /** 业务/组件错误码 */
    private final int code;

    /** 对外展示的错误信息 */
    private final String detailMessage;

    public AbstractRuntimeException(int code, String detailMessage) {
        super();
        this.code = code;
        this.detailMessage = detailMessage;
    }

    public AbstractRuntimeException(int code, String detailMessage, Throwable e) {
        super(e);
        this.code = code;
        this.detailMessage = detailMessage;
    }

    /** {@inheritDoc} 返回 {@link #detailMessage} */
    @Override
    public String getMessage() {
        return this.detailMessage;
    }

}
