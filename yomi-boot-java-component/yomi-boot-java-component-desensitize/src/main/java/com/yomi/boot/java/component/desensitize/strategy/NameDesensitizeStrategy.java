package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.AbstractDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.DesensitizeType;
import org.springframework.stereotype.Component;

/**
 * 中文姓名：保留首末字符，两字姓名只保留首字（张三 → 张*，张三丰 → 张*丰，欧阳娜娜 → 欧**娜）
 *
 * @author zwei
 */
@Component
public class NameDesensitizeStrategy extends AbstractDesensitizeStrategy {

    public NameDesensitizeStrategy() {
        super(DesensitizeType.NAME.name());
    }

    /** 按字数保留首尾字，两字名仅留首字 */
    @Override
    protected String doMask(String value) {
        int length = codePointLength(value);
        if (length <= 1) {
            return repeatMask(1);
        }
        if (length == 2) {
            return slice(value, 0, 1) + repeatMask(1);
        }
        return slice(value, 0, 1) + repeatMask(length - 2) + slice(value, length - 1, length);
    }

}
