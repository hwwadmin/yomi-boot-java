package com.yomi.boot.java.component.desensitize.strategy;

import com.yomi.boot.java.component.desensitize.DesensitizeType;
import com.yomi.boot.java.component.desensitize.KeepEdgeDesensitizeStrategy;
import org.springframework.stereotype.Component;

/**
 * 用户名/账号：保留前 1 后 1（zhangsan → z******n）
 *
 * @author zwei
 */
@Component
public class UsernameDesensitizeStrategy extends KeepEdgeDesensitizeStrategy {

    public UsernameDesensitizeStrategy() {
        super(DesensitizeType.USERNAME.name(), 1, 1);
    }

}
