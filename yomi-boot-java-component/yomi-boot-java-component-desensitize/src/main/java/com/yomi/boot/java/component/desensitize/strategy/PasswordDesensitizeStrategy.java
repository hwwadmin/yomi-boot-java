package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.AbstractDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.DesensitizeType;
import org.springframework.stereotype.Component;

/**
 * 密码：固定 6 个掩码字符，不保留原长度
 *
 * @author zwei
 */
@Component
public class PasswordDesensitizeStrategy extends AbstractDesensitizeStrategy {

    /** 掩码位数 */
    private static final int MASK_LENGTH = 6;

    public PasswordDesensitizeStrategy() {
        super(DesensitizeType.PASSWORD.name());
    }

    /** 固定 6 位掩码，不暴露原长度 */
    @Override
    protected String doMask(String value) {
        return repeatMask(MASK_LENGTH);
    }

}
