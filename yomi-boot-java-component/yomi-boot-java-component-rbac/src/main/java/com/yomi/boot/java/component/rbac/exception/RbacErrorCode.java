package com.yomi.boot.java.component.rbac.exception;

import com.yomi.boot.java.base.enums.StatusCode;
import lombok.Getter;

/**
 * RBAC 组件错误码
 * <p>
 * 403（{@link #FORBIDDEN}）表示无访问权限；500（{@link #EVAL_FAILED}）表示校验过程失败（如基础设施异常），与越权区分。
 *
 * @author zwei
 */
@Getter
public enum RbacErrorCode {

    FORBIDDEN("RBAC_FORBIDDEN", StatusCode.unauthorized.getCode(), "无访问权限"),
    EVAL_FAILED("RBAC_EVAL_FAILED", StatusCode.serverError.getCode(), "权限校验失败"),

    PERM_NOT_FOUND("RBAC_PERM_NOT_FOUND", StatusCode.bizError.getCode(), "权限不存在"),
    ROLE_NOT_FOUND("RBAC_ROLE_NOT_FOUND", StatusCode.bizError.getCode(), "角色不存在"),
    PERM_GROUP_NOT_FOUND("RBAC_PERM_GROUP_NOT_FOUND", StatusCode.bizError.getCode(), "权限组不存在"),
    CODE_CONFLICT("RBAC_CODE_CONFLICT", StatusCode.bizError.getCode(), "编码已存在"),
    BUILTIN_IMMUTABLE("RBAC_BUILTIN_IMMUTABLE", StatusCode.bizError.getCode(), "内置角色不可变更"),
    GRANT_SUPER_DENIED("RBAC_GRANT_SUPER_DENIED", StatusCode.bizError.getCode(), "仅超管可授予超管角色"),
    UNBIND_SELF_SUPER("RBAC_UNBIND_SELF_SUPER", StatusCode.bizError.getCode(), "禁止解绑自身超管角色"),
    API_KEY_CONFLICT("RBAC_API_KEY_CONFLICT", StatusCode.bizError.getCode(), "HTTP 方法与路径已存在"),
    MENU_PATH_CONFLICT("RBAC_MENU_PATH_CONFLICT", StatusCode.bizError.getCode(), "目录路径已存在"),
    CACHE_REFRESH_BUSY("RBAC_CACHE_REFRESH_BUSY", StatusCode.bizError.getCode(), "鉴权投影刷新进行中"),
    INVALID_INPUT("RBAC_INVALID_INPUT", StatusCode.bizError.getCode(), "参数不合法"),
    ;

    /** 组件内错误标识 */
    private final String code;

    /** HTTP 响应码 */
    private final int responseCode;

    /** 默认提示文案 */
    private final String message;

    RbacErrorCode(String code, int responseCode, String message) {
        this.code = code;
        this.responseCode = responseCode;
        this.message = message;
    }

}
