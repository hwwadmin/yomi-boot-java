package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 手机号：保留前 3 后 4（13812345678 → 138****5678）
 *
 * @author zwei
 */
@Component
public class MobileDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public MobileDesensitizeStrategy() {
        super(DesensitizeType.MOBILE.name(), 3, 4);
    }

}
