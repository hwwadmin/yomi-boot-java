package com.yomi.boot.java.component.token.session;

import com.yomi.boot.java.component.token.exception.TokenAuthException;
import com.yomi.boot.java.component.token.exception.TokenErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * 令牌会话请求上下文门面
 * <p>
 * 会话与原始令牌由鉴权拦截器写入请求域（见 {@link #ATTR_SESSION} / {@link #ATTR_TOKEN}），
 * 门面经 {@link RequestContextHolder} 读取当前请求，DispatcherServlet 每请求自动绑定与清理。
 * <p>
 * 注意：仅在请求线程内有效；{@code @Async} 或自建线程不自动携带会话。
 *
 * @author zwei
 */
public abstract class TokenContext {

    /** 会话快照在请求域中的属性名（鉴权拦截器写入） */
    public static final String ATTR_SESSION = TokenContext.class.getName() + ".SESSION";

    /** 原始令牌在请求域中的属性名（鉴权拦截器写入） */
    public static final String ATTR_TOKEN = TokenContext.class.getName() + ".TOKEN";

    private TokenContext() {
    }

    /**
     * 读取当前请求的会话快照，不存在返回 null
     */
    public static TokenSession session() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : (TokenSession) request.getAttribute(ATTR_SESSION);
    }

    /**
     * 读取当前请求的原始令牌值，不存在返回 null
     */
    public static String tokenValue() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : (String) request.getAttribute(ATTR_TOKEN);
    }

    /**
     * 读取当前操作者用户标识，不存在返回 null
     */
    public static Long operatorId() {
        TokenSession session = session();
        return session == null ? null : session.getUserId();
    }

    /**
     * 读取当前操作者用户标识，不存在时抛 {@link TokenAuthException}（TOKEN_INVALID）
     */
    public static Long requireOperatorId() {
        Long operatorId = operatorId();
        if (operatorId == null) {
            throw TokenAuthException.of(TokenErrorCode.INVALID);
        }
        return operatorId;
    }

    /**
     * 读取当前会话 {@link TokenSession#getContext()} 中的项；无请求会话或 key 为空时返回 null
     */
    public static Object getContextValue(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        TokenSession session = session();
        if (session == null) {
            return null;
        }
        Map<String, Object> ctx = session.getContext();
        return ctx == null ? null : ctx.get(key);
    }

    /**
     * 向当前请求绑定的会话快照写入 context 项（仅本请求内存态，不写回 Redis）。
     * <p>跨请求保留须在 {@link com.yomi.boot.java.component.token.ITokenService#issue} 时写入快照，
     * 或后续扩展显式持久化 API；滑动续期也不会自动带上本请求内的修改。</p>
     *
     * @throws TokenAuthException 无会话时 TOKEN_INVALID
     * @throws IllegalArgumentException key 为空或 value 为 null
     */
    public static void putContextValue(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("context key 不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("context value 不能为 null");
        }
        TokenSession session = session();
        if (session == null) {
            throw TokenAuthException.of(TokenErrorCode.INVALID);
        }
        Map<String, Object> ctx = session.getContext();
        if (ctx == null) {
            ctx = new HashMap<>();
            session.setContext(ctx);
        }
        ctx.put(key, value);
    }

    /**
     * 获取当前线程绑定的请求对象，非请求线程返回 null
     */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

}
