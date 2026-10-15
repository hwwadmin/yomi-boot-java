package com.yomi.boot.java.component.oss.model;

import lombok.Builder;
import lombok.Value;

/**
 * {@link com.yomi.boot.java.component.oss.OssStorage#putObject} 可选参数。
 *
 * @author zwei
 */
@Value
@Builder
public class PutObjectOptions {

    /** Content-Type，空则由存储端默认 */
    String contentType;

    /** 仅指定 Content-Type 的便捷构造 */
    public static PutObjectOptions ofContentType(String contentType) {
        return PutObjectOptions.builder().contentType(contentType).build();
    }

}
