package com.yomi.boot.java.component.oss.exception;

import lombok.Getter;

/**
 * 对象存储操作异常，由各 {@link com.yomi.boot.java.component.oss.OssStorage} 实现抛出。
 *
 * @author zwei
 */
@Getter
public class OssException extends RuntimeException {

    /** 错误分类，供调用方分支处理 */
    private final OssErrorCode errorCode;

    private OssException(OssErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 按错误码与消息创建异常 */
    public static OssException of(OssErrorCode errorCode, String message) {
        return new OssException(errorCode, message, null);
    }

    /** 按错误码与消息创建异常，并附带原因链 */
    public static OssException of(OssErrorCode errorCode, String message, Throwable cause) {
        return new OssException(errorCode, message, cause);
    }

    /** 是否为「对象不存在」 */
    public boolean isNotFound() {
        return errorCode == OssErrorCode.NOT_FOUND;
    }

}
