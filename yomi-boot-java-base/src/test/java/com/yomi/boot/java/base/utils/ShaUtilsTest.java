package com.yomi.boot.java.base.utils;

import com.yomi.boot.java.base.enums.ShaAlgorithm;
import com.yomi.boot.java.base.exception.AssertException;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 摘要工具：算法可用性与黄金向量
 */
class ShaUtilsTest {

    @Test
    void allAlgorithmsAvailableInJca() {
        for (ShaAlgorithm algorithm : ShaAlgorithm.values()) {
            assertDoesNotThrow(() -> MessageDigest.getInstance(algorithm.getAlgorithmName()),
                    algorithm.name());
        }
    }

    @Test
    void utf8NullTextFailsFastWithAssert() {
        // null 明文抛 AssertException，digestHexUtf8 与 digestUtf8 行为一致
        assertThrows(AssertException.class, () -> ShaUtils.digestHexUtf8(null));
        assertThrows(AssertException.class, () -> ShaUtils.digestHexUtf8(null, ShaAlgorithm.SHA256));
        assertThrows(AssertException.class, () -> ShaUtils.digestUtf8(null, ShaAlgorithm.SHA256));
    }

    @Test
    void sha256EmptyGoldenHex() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ShaUtils.digestHex(new byte[0])
        );
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ShaUtils.digestHexUtf8("")
        );
    }

    @Test
    void sha256KnownString() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ShaUtils.digestHexUtf8("abc")
        );
    }

    @Test
    void hexAndBase64AlignWithGoldenVector() throws Exception {
        byte[] data = "yomi".getBytes(StandardCharsets.UTF_8);
        byte[] raw = ShaUtils.digest(data, ShaAlgorithm.SHA512);
        assertEquals(64, raw.length);
        // 独立编码实现（commons-codec Hex）对齐原始摘要
        assertEquals(Hex.encodeHexString(raw), ShaUtils.digestHex(data, ShaAlgorithm.SHA512));
        // Base64 用黄金向量校验：与实现自身编码对比恒真
        byte[] emptyGolden = Hex.decodeHex(
                "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                        + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e");
        assertEquals(Base64.getEncoder().encodeToString(emptyGolden),
                ShaUtils.digestBase64(new byte[0], ShaAlgorithm.SHA512));
    }

    @Test
    void sha512GoldenHex() {
        assertEquals(
                "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                        + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
                ShaUtils.digestHexUtf8("", ShaAlgorithm.SHA512)
        );
        assertEquals(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                ShaUtils.digestHexUtf8("abc", ShaAlgorithm.SHA512)
        );
    }

    @Test
    void sha3RoundTripLength() {
        byte[] data = "sha3".getBytes(StandardCharsets.UTF_8);
        assertEquals(32, ShaUtils.digest(data, ShaAlgorithm.SHA3_256).length);
        assertEquals(64, ShaUtils.digest(data, ShaAlgorithm.SHA3_512).length);
    }

}
