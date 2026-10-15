package com.yomi.boot.java.component.rbac.enums;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 权限类型
 *
 * @author zwei
 */
@Getter
public enum RbacPermType {

    MENU("menu"),
    API("api"),
    ;

    /** 持久化/接口用的类型码 */
    private final String code;

    RbacPermType(String code) {
        this.code = code;
    }

    /** 解析权限类型；无法识别时返回 null */
    public static RbacPermType of(String raw) {
        String t = StringUtils.trimToEmpty(raw);
        for (RbacPermType value : values()) {
            if (value.code.equalsIgnoreCase(t)) {
                return value;
            }
        }
        return null;
    }

}
