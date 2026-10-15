package com.yomi.boot.java.component.token;

import com.yomi.boot.java.component.token.session.TokenContext;
import com.yomi.boot.java.core.utils.SpringUtils;

/**
 * token 工具类
 *
 * @author zwei
 */
public class TokenUtils {

    private TokenUtils() {

    }

    /**
     * 获取 token 服务
     */
    public static ITokenService getService() {
        return SpringUtils.getBean(ITokenService.class);
    }

    /**
     * 获取当前用户 id
     */
    public static Long getUserId() {
        return getService().getUserId();
    }

    /**
     * 读取当前会话 context 项
     */
    public static Object getContextValue(String key) {
        return TokenContext.getContextValue(key);
    }

    /**
     * 读取当前会话 context 项
     */
    public static <T> T getContextValue(String key, Class<T> clazz) {
        var obj = TokenContext.getContextValue(key);
        if (obj == null) {
            return null;
        }
        return clazz.cast(obj);
    }

    /**
     * 写入当前请求会话 context 项
     */
    public static void putContextValue(String key, Object value) {
        TokenContext.putContextValue(key, value);
    }

}
