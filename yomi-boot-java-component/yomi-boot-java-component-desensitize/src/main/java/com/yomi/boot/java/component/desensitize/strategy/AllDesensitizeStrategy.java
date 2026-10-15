package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 整体遮蔽：全字符替换为掩码字符，保留长度（abc → ***）
 *
 * @author zwei
 */
@Component
public class AllDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public AllDesensitizeStrategy() {
        super(DesensitizeType.ALL.name(), 0, 0);
    }

}
