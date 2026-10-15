package com.yomi.boot.java.component.captcha.store;

import com.yomi.boot.java.component.captcha.MutableTicker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地存储：一次性消费、过期、容量上限
 */
class LocalCaptchaStoreTest {

    private static final Duration TTL = Duration.ofSeconds(10);

    private final MutableTicker ticker = new MutableTicker();

    @Test
    void returnsValueOnceThenNothing() {
        LocalCaptchaStore store = new LocalCaptchaStore(TTL, 100, ticker);
        store.save("id", "AB12");
        assertEquals("AB12", store.remove("id"));
        assertNull(store.remove("id"), "一次性消费：第二次必须为空");
    }

    @Test
    void expiredEntryReturnsNull() {
        LocalCaptchaStore store = new LocalCaptchaStore(TTL, 100, ticker);
        store.save("id", "AB12");
        ticker.advance(Duration.ofSeconds(11));
        assertNull(store.remove("id"), "过期后不应再返回答案");
    }

    @Test
    void maxEntriesEvictsInsteadOfGrowingUnbounded() {
        LocalCaptchaStore store = new LocalCaptchaStore(Duration.ofMinutes(1), 2, ticker);
        store.save("a", "1");
        store.save("b", "2");
        store.save("c", "3");
        int alive = 0;
        for (String id : new String[]{"a", "b", "c"}) {
            if (store.remove(id) != null) {
                alive++;
            }
        }
        assertTrue(alive <= 2, "条目数不能超过上限，实际存活 " + alive);
        assertEquals("local", store.name());
    }

}
