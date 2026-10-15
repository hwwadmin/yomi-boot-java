package com.yomi.boot.java.component.token.store;

import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenKeysTest {

    private static final String PREFIX = "yomi:test:token";

    @Test
    void userIndexAndSessionShareClusterSlot() {
        TokenKeys keys = new TokenKeys(PREFIX);
        Long userId = 42L;
        String storage = TokenKeys.hashToken("sample-token");

        int indexSlot = SlotHash.getSlot(keys.userTokensKey(userId));
        int sessionSlot = SlotHash.getSlot(keys.sessionKeyByStorageToken(userId, storage));
        int lockSlot = SlotHash.getSlot(keys.issueLockKey(userId));

        assertEquals(indexSlot, sessionSlot);
        assertEquals(indexSlot, lockSlot);
    }

    @Test
    void routeKeyUsesTokenHashNotUserTag() {
        TokenKeys keys = new TokenKeys(PREFIX);
        String route = keys.routeKey("abc");
        assertTrue(route.startsWith(PREFIX + ":rt:"));
        assertTrue(route.endsWith(TokenKeys.hashToken("abc")));
    }

    @Test
    void rejectsHashTagCharactersInPrefix() {
        assertThrows(IllegalStateException.class, () -> new TokenKeys("bad{prefix"));
        assertThrows(IllegalStateException.class, () -> new TokenKeys("bad}prefix"));
    }

}
