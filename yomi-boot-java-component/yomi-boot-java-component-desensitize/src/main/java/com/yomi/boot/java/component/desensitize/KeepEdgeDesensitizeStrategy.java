package com.yomi.boot.java.component.desensitize;

/**
 * 保留前后位数的脱敏策略：保留前 prefixKeep、后 suffixKeep 个字符，其余遮蔽；长度不足则整体遮蔽
 * <p>
 * 内置策略中手机号、身份证等继承本类；宿主自定义位数（工号、学号等）直接实例化或继承本类，
 * 再注册为 Bean 即可。
 *
 * @author zwei
 */
public class KeepEdgeDesensitizeStrategy extends AbstractDesensitizeStrategy {

    /** 保留前/后位数（code point） */
    private final int prefixKeep;

    /** 保留前/后位数（code point） */
    private final int suffixKeep;

    public KeepEdgeDesensitizeStrategy(String name, int prefixKeep, int suffixKeep) {
        super(name);
        requireKeep(prefixKeep, suffixKeep);
        this.prefixKeep = prefixKeep;
        this.suffixKeep = suffixKeep;
    }

    /** 按配置的保留位数遮蔽中间段 */
    @Override
    protected String doMask(String value) {
        return keepEdge(value, prefixKeep, suffixKeep);
    }

}
