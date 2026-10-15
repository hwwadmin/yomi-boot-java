package com.yomi.boot.java.component.rbac.enums;

import lombok.Getter;

/**
 * 启停状态（0 禁用 / 1 启用）
 *
 * @author zwei
 */
@Getter
public enum RbacStatus {

    DISABLED(0),
    ACTIVE(1),
    ;

    /** 启用/禁用状态码 */
    private final int code;

    RbacStatus(int code) {
        this.code = code;
    }

    /** 解析状态；null 或非 0 均视为启用 */
    public static RbacStatus of(Integer raw) {
        if (raw == null) {
            return ACTIVE;
        }
        return raw == DISABLED.code ? DISABLED : ACTIVE;
    }

    /** 解析状态并返回整型 code */
    public static int orDefault(Integer raw) {
        return of(raw).code;
    }

}
