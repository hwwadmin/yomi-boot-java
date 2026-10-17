package com.yomi.boot.java.component.token.web;

import com.yomi.boot.java.component.token.ITokenService;
import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.exception.TokenErrorCode;
import com.yomi.boot.java.component.token.session.TokenContext;
import com.yomi.boot.java.component.token.session.TokenSession;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 令牌鉴权拦截器
 * <p>
 * 由 {@link TokenWebMvcConfig} 注册（默认作用于全部路径，排除 {@code yomi.token.skip-validate-paths}）：
 * 从 Authorization 提取令牌并校验，成功后将会话与原始令牌挂到请求域供业务读取。
 * 校验中的基础设施异常（如 Redis 故障）统一包装为 TOKEN_VALIDATE_FAILED（响应码 500），不向客户端暴露细节。
 * <p>错误转发（{@link DispatcherType#ERROR}，如 404/500 转发到 {@code /error}）直接放行：
 * 放行后真实状态得以保留，客户端不会把"接口不存在/服务出错"误判为"没登录"
 * </p>
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class TokenInterceptor implements HandlerInterceptor {

    /** Authorization 头 Bearer 前缀（大小写不敏感） */
    private static final String BEARER_PREFIX = "bearer ";
    /** 仅含 Bearer 而无令牌内容时的头值 */
    private static final String BEARER_SCHEME = "bearer";

    /** 令牌校验与续期 */
    private final ITokenService tokenService;

    /** 提取并校验令牌，将会话与原始令牌写入请求域 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // 错误转发交给标准错误渲染：保留 404/500 的真实状态，不因未携带令牌而改写
            return true;
        }
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        String token = extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (StringUtils.isBlank(token)) {
            throw TokenAuthException.of(TokenErrorCode.REQUIRED);
        }

        TokenSession session;
        try {
            session = tokenService.validate(token);
        } catch (TokenAuthException e) {
            throw e;
        } catch (Exception e) {
            throw TokenAuthException.of(TokenErrorCode.VALIDATE_FAILED, null, e);
        }

        request.setAttribute(TokenContext.ATTR_SESSION, session);
        request.setAttribute(TokenContext.ATTR_TOKEN, token);
        return true;
    }

    /**
     * 从 Authorization 请求头提取令牌：支持 {@code Bearer xxx} 与裸令牌两种形态，大小写不敏感；
     * 仅携带 Bearer 前缀而无可取内容时视为未携带，返回空串
     */
    private static String extractToken(String authorizationHeader) {
        String trimmed = StringUtils.trimToEmpty(authorizationHeader);
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return trimmed.substring(BEARER_PREFIX.length()).trim();
        }
        if (BEARER_SCHEME.equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed;
    }

}
