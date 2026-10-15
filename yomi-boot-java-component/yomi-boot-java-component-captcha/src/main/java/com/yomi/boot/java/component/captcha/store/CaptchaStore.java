package com.yomi.boot.java.component.captcha.store;

/**
 * 验证码挑战存储
 * <p>
 * 只存答案（key 为随机句柄），有效期由实现按配置的 ttl 承担。
 * 宿主可提供自己的 {@code CaptchaStore} Bean 顶替内置实现。
 *
 * @author zwei
 */
public interface CaptchaStore {

    /**
     * 保存挑战答案
     */
    void save(String captchaId, String answer);

    /**
     * 取出并删除（原子）：一次性消费，不存在/已过期返回 null；并发调用只有一个能拿到答案
     */
    String remove(String captchaId);

    /**
     * 实现名（装配日志展示，避免"以为用了 Redis 实际是本地"的误会）
     */
    String name();

}
