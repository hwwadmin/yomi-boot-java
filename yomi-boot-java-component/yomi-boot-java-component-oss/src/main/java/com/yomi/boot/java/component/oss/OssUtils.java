package com.yomi.boot.java.component.oss;

import com.yomi.boot.java.core.utils.SpringUtils;

/**
 * OSS 静态入口，等价于从容器取 {@link OssStorage} Bean。
 * <p>
 * 需在 {@code yomi.oss.enabled=true} 且 Spring 上下文已启动后调用。
 *
 * @author zwei
 */
public final class OssUtils {

    private OssUtils() {
    }

    /** 获取已装配的 {@link OssStorage} */
    public static OssStorage getStorage() {
        return SpringUtils.getBean(OssStorage.class);
    }

}
