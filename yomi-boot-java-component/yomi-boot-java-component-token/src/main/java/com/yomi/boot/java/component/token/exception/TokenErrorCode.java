package com.yomi.boot.java.component.token.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import lombok.Getter;

/**
 * 令牌组件对外错误码
 * <p>
 * 响应码分层（与 {@link StatusCode} 码段约定一致）：
 * <ul>
 *     <li>401 鉴权失败：缺令牌 / 令牌无效，由 {@link TokenAuthException} 经组件的令牌异常处理器输出；</li>
 *     <li>500 基础设施故障：校验过程异常（如 Redis 不可用），不向客户端暴露细节——
 *         用 500 与 401 区分：前端不会按"登录失效"清 token 跳登录，监控也不会把故障计成掉线；</li>
 *     <li>601 业务与内部错误：由 {@link com.yomi.boot.java.base.exception.BizException} 经全局异常处理器输出。</li>
 * </ul>
 *
 * @author zwei
 */
@Getter
public enum TokenErrorCode {

    /** 缺少访问令牌 */
    REQUIRED("TOKEN_REQUIRED", StatusCode.invalidToken.getCode(), "缺少访问令牌"),
    /** 令牌无效或已过期 */
    INVALID("TOKEN_INVALID", StatusCode.invalidToken.getCode(), "令牌无效或已过期"),
    /** 令牌校验失败（校验过程基础设施异常；500 与"登录失效"区分，不向客户端暴露细节） */
    VALIDATE_FAILED("TOKEN_VALIDATE_FAILED", StatusCode.serverError.getCode(), "令牌校验失败"),
    /** 令牌会话快照不合法 */
    SESSION_INVALID("TOKEN_SESSION_INVALID", StatusCode.bizError.getCode(), "令牌会话不合法"),
    /** 令牌签发繁忙（单点登录等锁超时） */
    ISSUE_BUSY("TOKEN_ISSUE_BUSY", StatusCode.bizError.getCode(), "令牌签发繁忙，请稍后重试"),
    /** 调用参数不合法 */
    INVALID_INPUT("INVALID_INPUT", StatusCode.bizError.getCode(), "参数不合法"),
    ;

    /** 对外错误码 */
    private final String code;

    /** 响应码 */
    private final int responseCode;

    /** 默认提示消息 */
    private final String message;

    TokenErrorCode(String code, int responseCode, String message) {
        this.code = code;
        this.responseCode = responseCode;
        this.message = message;
    }

}
