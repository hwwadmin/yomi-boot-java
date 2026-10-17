package com.yomi.boot.java.component.desensitize;

/**
 * 内置脱敏策略名称：各项与 {@code strategy} 包下同名的内置策略类一一对应（各策略类以 {@code @Component} 自注册），
 * 规则见对应策略类
 * <p>
 * 本枚举仅用于内置策略；宿主自定义策略用普通名称，注册见 {@link DesensitizeStrategyRegistry}。
 *
 * @author zwei
 */
public enum DesensitizeType {

    /** 整体遮蔽（保留长度） */
    ALL,

    /** 中文姓名 */
    NAME,

    /** 手机号 */
    MOBILE,

    /** 固定电话 */
    FIXED_PHONE,

    /** 身份证 */
    ID_CARD,

    /** 银行卡 */
    BANK_CARD,

    /** 邮箱 */
    EMAIL,

    /** 地址 */
    ADDRESS,

    /** 车牌号 */
    CAR_NO,

    /** 护照 */
    PASSPORT,

    /** 用户名/账号 */
    USERNAME,

    /** 密码 */
    PASSWORD,

    ;

}
