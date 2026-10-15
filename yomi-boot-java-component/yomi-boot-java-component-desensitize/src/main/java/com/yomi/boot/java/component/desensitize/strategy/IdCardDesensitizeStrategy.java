package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 身份证：保留前 6 后 4（110101199003074567 → 110101********4567）
 *
 * @author zwei
 */
@Component
public class IdCardDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public IdCardDesensitizeStrategy() {
        super(DesensitizeType.ID_CARD.name(), 6, 4);
    }

}
