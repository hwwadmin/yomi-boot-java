package com.yomi.boot.java.component.rbac.eval;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 索引缓存：mem 命中路径上快照实例 10 秒才换一次，索引不应每请求重建
 */
class RbacAuthIndexCacheTest {

    @Test
    void reusesIndexForSameSnapshotInstance() {
        RbacAuthIndexCache cache = new RbacAuthIndexCache();
        RbacSnapshot snapshot = snapshot();

        RbacAuthEvaluator.EvalIndex first = cache.indexFor(snapshot);
        RbacAuthEvaluator.EvalIndex second = cache.indexFor(snapshot);

        assertSame(first, second, "同一快照实例必须复用索引");
    }

    @Test
    void rebuildsForNewSnapshotInstance() {
        RbacAuthIndexCache cache = new RbacAuthIndexCache();

        RbacAuthEvaluator.EvalIndex first = cache.indexFor(snapshot());
        RbacAuthEvaluator.EvalIndex second = cache.indexFor(snapshot());

        assertNotSame(first, second, "快照换了（刷新/回源）必须重建索引");
    }

    /** 构造 RBAC 测试快照 */
    private static RbacSnapshot snapshot() {
        RbacSnapshot snap = RbacSnapshot.empty();
        RbacRole role = new RbacRole();
        role.setId(1L);
        RbacPerm perm = new RbacPerm();
        perm.setId(2L);
        snap.setRoles(List.of(role));
        snap.setPerms(List.of(perm));
        return snap;
    }

}
