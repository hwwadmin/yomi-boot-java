package com.yomi.boot.java.component.oss.exception;

import lombok.Getter;

/**
 * OSS 组件错误分类（可与日志、监控对接）。
 *
 * @author zwei
 */
@Getter
public enum OssErrorCode {

    /** 对象不存在 */
    NOT_FOUND("OSS_NOT_FOUND"),
    /** 参数或配置不合法 */
    INVALID_ARGUMENT("OSS_INVALID_ARGUMENT"),
    /** SDK 或网络等底层失败 */
    OPERATION_FAILED("OSS_OPERATION_FAILED"),
    ;

    /** 对外稳定错误码字符串 */
    private final String code;

    OssErrorCode(String code) {
        this.code = code;
    }

}
