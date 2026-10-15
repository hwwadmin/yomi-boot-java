package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 护照：保留前 2 后 2（E12345678 → E1*****78）
 *
 * @author zwei
 */
@Component
public class PassportDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public PassportDesensitizeStrategy() {
        super(DesensitizeType.PASSPORT.name(), 2, 2);
    }

}
