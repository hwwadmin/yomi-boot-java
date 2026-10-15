package com.yomi.boot.java.component.oss.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * 对象元数据，由 {@link com.yomi.boot.java.component.oss.OssStorage#statObject(String)} 返回。
 *
 * @author zwei
 */
@Value
@Builder
public class ObjectMeta {

    /** MIME 类型，可能为空 */
    String contentType;
    /** 字节长度，未知时为 0 */
    long contentLength;
    /** 实体标签，可能为空 */
    String eTag;
    /** 最后修改时间，可能为空 */
    Instant lastModified;

}
