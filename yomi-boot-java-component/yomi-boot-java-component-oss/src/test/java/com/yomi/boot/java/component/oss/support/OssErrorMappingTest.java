package com.yomi.boot.java.component.oss.support;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OssErrorMappingTest {

    @Test
    void notFoundWalksCauseChain() {
        S3Exception inner = (S3Exception) S3Exception.builder().statusCode(404).build();
        Exception wrapped = new Exception("wrap", inner);
        assertTrue(OssErrorMapping.isNotFound(wrapped, OssErrorMapping.s3NotFound()));
    }

    @Test
    void unrelatedCauseReturnsFalse() {
        Exception ex = new Exception("other", new IllegalStateException());
        assertFalse(OssErrorMapping.isNotFound(ex, OssErrorMapping.s3NotFound()));
    }

}
