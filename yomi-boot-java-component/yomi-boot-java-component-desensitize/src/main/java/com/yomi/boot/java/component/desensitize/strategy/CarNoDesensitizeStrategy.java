package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 车牌号：保留前 2 后 2（浙A12345 → 浙A***45）
 *
 * @author zwei
 */
@Component
public class CarNoDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public CarNoDesensitizeStrategy() {
        super(DesensitizeType.CAR_NO.name(), 2, 2);
    }

}
