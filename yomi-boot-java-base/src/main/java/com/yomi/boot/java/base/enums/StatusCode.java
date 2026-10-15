package com.yomi.boot.java.base.enums;

import lombok.Getter;

/**
 * 响应码
 * <p>
 * 码段划分：
 * <ul>
 *     <li>-1 与 4xx/5xx：与 HTTP 语义对齐，用于系统级/框架级错误</li>
 *     <li>6xx：业务与内部错误，与 HTTP 语义解耦（避免业务失败被监控按 500 系统故障处理）</li>
 * </ul>
 * 参数校验使用 422（HTTP Unprocessable Content），不占用 412（Precondition Failed）。
 *
 * @author zwei
 */
@Getter
public enum StatusCode {

    /** 成功 */
    succeed(200),

    /** 未知错误：服务器繁忙，请稍后重试 */
    unknown(-1),
    /** 通用客户端错误 */
    commonError(400),
    /** 登录失效 */
    invalidToken(401),
    /** 无访问权限 */
    unauthorized(403),
    /** 访问资源不存在 */
    notFound(404),
    /** 错误的请求方式 */
    methodNotAllowed(405),
    /** 不支持的请求媒体类型 */
    unsupportedMediaType(415),
    /** 参数校验异常 */
    parameterError(422),
    /** 系统内部错误 */
    serverError(500),
    /** 功能未实现 */
    notImplemented(501),

    /** 业务异常（{@link com.yomi.boot.java.base.exception.BizException} 默认码） */
    bizError(601),
    /** 工具类异常 */
    utilsError(604),
    /** 数据库操作失败 */
    databaseError(608),
    /** 断言异常 */
    assertError(610),
    ;

    /**
     * 响应码
     */
    final int code;

    StatusCode(int code) {
        this.code = code;
    }

    /**
     * 按响应码查找枚举，未匹配时返回 {@link #commonError}
     */
    public static StatusCode getEnum(int code) {
        for (StatusCode anEnum : values()) {
            if (anEnum.code == code) return anEnum;
        }
        return commonError;
    }

}
