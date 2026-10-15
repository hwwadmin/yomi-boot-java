package com.yomi.boot.java.component.rbac.enums;

/**
 * HTTP 鉴权路径取值方式
 *
 * @author zwei
 */
public enum RbacHttpPathStyle {

    /** 完整请求 URI（含 context path），对应 {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()} */
    REQUEST_URI,
    /** Servlet 映射路径，对应 {@link jakarta.servlet.http.HttpServletRequest#getServletPath()} */
    SERVLET_PATH,
    ;

}
