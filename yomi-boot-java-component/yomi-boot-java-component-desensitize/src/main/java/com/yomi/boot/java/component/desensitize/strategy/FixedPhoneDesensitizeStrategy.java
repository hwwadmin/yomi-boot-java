package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 固定电话：保留前 4 后 2（0571-88888888 → 0571*******88）
 *
 * @author zwei
 */
@Component
public class FixedPhoneDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public FixedPhoneDesensitizeStrategy() {
        super(DesensitizeType.FIXED_PHONE.name(), 4, 2);
    }

}
