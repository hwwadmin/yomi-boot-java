package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 地址：保留前 6（浙江省杭州市西湖区文三路 → 浙江省杭州市******）
 *
 * @author zwei
 */
@Component
public class AddressDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public AddressDesensitizeStrategy() {
        super(DesensitizeType.ADDRESS.name(), 6, 0);
    }

}
