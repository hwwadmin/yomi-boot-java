package com.yomi.boot.java.core.aop;

import com.yomi.boot.java.base.utils.XssUtils;
import com.yomi.boot.java.core.annotation.Xss;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

/**
 * 自定义xss校验注解实现
 *
 * @author zwei
 */
public class XssValidator implements ConstraintValidator<Xss, String> {

    /** 校验字符串不含 XSS 危险片段 */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        if (StringUtils.isBlank(value)) return true;
        return !XssUtils.isXssAttack(value);
    }

}
