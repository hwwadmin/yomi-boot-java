package com.yomi.boot.java.component.oss.support;

import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.yomi.boot.java.component.oss.exception.OssErrorCode;
import com.yomi.boot.java.component.oss.exception.OssException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.function.Predicate;

/**
 * SDK 异常 → {@link OssException}，沿 cause 链识别「对象不存在」。
 *
 * @author zwei
 */
public final class OssErrorMapping {

    private OssErrorMapping() {
    }

    /**
     * 映射为 {@link OssException}；命中 notFound 谓词时返回 {@link OssErrorCode#NOT_FOUND}
     */
    public static OssException map(Exception ex, String message, Predicate<Throwable> notFoundPredicate) {
        if (isNotFound(ex, notFoundPredicate)) {
            return OssException.of(OssErrorCode.NOT_FOUND, "对象不存在");
        }
        return OssException.of(OssErrorCode.OPERATION_FAILED, message, ex);
    }

    /** 沿 cause 链判断是否为「对象不存在」 */
    public static boolean isNotFound(Throwable ex, Predicate<Throwable> notFoundPredicate) {
        Throwable current = ex;
        while (current != null) {
            if (notFoundPredicate.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** AWS S3 SDK 的 404 / NoSuchKey 等判定 */
    public static Predicate<Throwable> s3NotFound() {
        return throwable -> {
            if (!(throwable instanceof S3Exception s3)) {
                return false;
            }
            if (s3.statusCode() == 404) {
                return true;
            }
            String code = s3.awsErrorDetails() != null ? s3.awsErrorDetails().errorCode() : null;
            return "NoSuchKey".equals(code) || "NotFound".equals(code) || "NoSuchBucket".equals(code);
        };
    }

    /** 阿里云 OSS SDK 的 404 / NoSuchKey 等判定 */
    public static Predicate<Throwable> aliOssNotFound() {
        return throwable -> {
            if (!(throwable instanceof ServiceException se)) {
                return false;
            }
            if (se.statusCode() == 404) {
                return true;
            }
            String code = se.errorCode();
            return "NoSuchKey".equals(code) || "NotFound".equals(code) || "NoSuchBucket".equals(code);
        };
    }

}
