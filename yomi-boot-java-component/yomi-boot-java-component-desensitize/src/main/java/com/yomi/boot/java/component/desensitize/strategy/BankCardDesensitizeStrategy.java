package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 银行卡：保留前 4 后 4（6222020200112233445 → 6222***********3445）
 *
 * @author zwei
 */
@Component
public class BankCardDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public BankCardDesensitizeStrategy() {
        super(DesensitizeType.BANK_CARD.name(), 4, 4);
    }

}
