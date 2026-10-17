package com.yomi.boot.java.component.captcha;

import com.yomi.boot.java.component.captcha.core.CaptchaChallenge;
import com.yomi.boot.java.component.captcha.core.CaptchaType;
import com.yomi.boot.java.component.captcha.core.CaptchaVerifyResult;
import com.yomi.boot.java.component.captcha.exception.CaptchaException;

/**
 * 验证码服务（服务层门面）
 * <p>
 * 组件不提供 HTTP 接口，接口由业务自行封装（响应模型、放行路径、no-store 头都归业务）。
 * 契约：
 * <ul>
 *     <li>生成：随机句柄 + 图片（不含答案），答案存于存储层并带有效期；</li>
 *     <li>校验：一次性消费，答案对错都让挑战立即失效（防爆破、防重放）；</li>
 *     <li>基础设施异常（存储/渲染等）抛 {@link CaptchaException}（500），
 *     与"输错"结果区分；调用方如需放行，自行捕获处理。</li>
 * </ul>
 *
 * @author zwei
 */
public interface ICaptchaService {

    /**
     * 生成挑战；{@code type} 传 null 用配置的默认类型
     */
    CaptchaChallenge generate(CaptchaType type);

    /**
     * 校验答案（一次性消费）
     * <p>
     * 句柄或答案为空、答案不匹配返回 MISMATCH；挑战不存在/已过期/已被消费返回 EXPIRED。
     */
    CaptchaVerifyResult verify(String captchaId, String answer);

    /**
     * 主动作废挑战（不做任何处理当句柄为空）
     */
    void invalidate(String captchaId);

}
