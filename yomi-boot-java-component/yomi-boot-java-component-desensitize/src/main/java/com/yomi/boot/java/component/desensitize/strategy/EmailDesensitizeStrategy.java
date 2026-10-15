package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.AbstractDesensitizeStrategy;
import com.yomi.boot.java.component.desensitize.DesensitizeType;
import org.springframework.stereotype.Component;

/**
 * 邮箱：本地部分保留首字符，域名保留（abcdef@qq.com → a*****@qq.com）；非邮箱形态只保留首字符
 *
 * @author zwei
 */
@Component
public class EmailDesensitizeStrategy extends AbstractDesensitizeStrategy {

    public EmailDesensitizeStrategy() {
        super(DesensitizeType.EMAIL.name());
    }

    /** 本地部分保留首字，{@code @} 后域名不脱敏 */
    @Override
    protected String doMask(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return keepEdge(value, 1, 0);
        }
        return keepEdge(value.substring(0, at), 1, 0) + value.substring(at);
    }

}
