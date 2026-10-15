package com.yomi.boot.java.base.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.yomi.boot.java.base.enums.StatusCode;
import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 通用返回响应对象
 *
 * @author zwei
 */
@Getter
@JsonPropertyOrder({"code", "errorCode", "debugMessage", "timestamp", "data"})
public class Response<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 响应码
     */
    protected int code;

    /**
     * 业务错误码
     */
    protected String errorCode;

    /**
     * 响应时间戳（毫秒）
     */
    protected long timestamp;

    /**
     * 响应数据
     */
    protected T data;

    /**
     * 调试用响应消息，这个消息只用于调试前端不要直接展示
     */
    protected String debugMessage;

    protected Response() {
        this.timestamp = Instant.now().toEpochMilli();
    }

    /** 无数据的成功响应 */
    public static <T> Response<T> success() {
        var r = new Response<T>();
        r.code = StatusCode.succeed.getCode();
        return r;
    }

    /** 带数据的成功响应 */
    public static <T> Response<T> success(T data) {
        var r = new Response<T>();
        r.code = StatusCode.succeed.getCode();
        r.data = data;
        return r;
    }

    /** 指定响应码的失败响应 */
    public static <T> Response<T> fail(int code, String errorCode, String debugMessage) {
        var r = new Response<T>();
        r.code = code;
        r.errorCode = errorCode;
        r.debugMessage = debugMessage;
        return r;
    }

    /** 业务失败响应（响应码为 {@link StatusCode#bizError}） */
    public static <T> Response<T> fail(String errorCode, String debugMessage) {
        var r = new Response<T>();
        r.code = StatusCode.bizError.getCode();
        r.errorCode = errorCode;
        r.debugMessage = debugMessage;
        return r;
    }

}
