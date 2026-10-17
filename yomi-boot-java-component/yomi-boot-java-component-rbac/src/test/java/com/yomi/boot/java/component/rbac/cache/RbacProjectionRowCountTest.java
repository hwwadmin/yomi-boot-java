package com.yomi.boot.java.component.rbac.cache;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投影版本完整性：指针 n 须与实际读到的行数一致——
 * 半截读/Hash 被淘汰/坏字段被跳过都算不可读，走回源重建；当成完整快照会在 mem 缓存 10 秒内持续生效
 */
class RbacProjectionRowCountTest {

    @Test
    void completeSnapshotPasses() {
        assertTrue(RbacProjectionStore.hasExpectedRowCount(snapshotWith(3), 3));
    }

    @Test
    void truncatedSnapshotIsRejected() {
        assertFalse(RbacProjectionStore.hasExpectedRowCount(snapshotWith(1), 3),
                "行数不足（半截读/被淘汰）必须判定为不可读");
    }

    @Test
    void emptyLibraryIsValid() {
        assertTrue(RbacProjectionStore.hasExpectedRowCount(snapshotWith(0), 0), "空库（n=0 且快照为空）合法");
    }

    @Test
    void rowsPresentButPointerEmptyIsRejected() {
        assertFalse(RbacProjectionStore.hasExpectedRowCount(snapshotWith(2), 0),
                "指针称空却有数据：指针与版本不匹配，应回源重建");
    }

    @Test
    void nullSnapshotIsRejected() {
        assertFalse(RbacProjectionStore.hasExpectedRowCount(null, 0));
    }

    /** 按行数构造指定大小的快照 */
    private static RbacSnapshot snapshotWith(int rows) {
        RbacSnapshot snap = RbacSnapshot.empty();
        List<RbacPerm> perms = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            RbacPerm perm = new RbacPerm();
            perm.setId((long) i);
            perms.add(perm);
        }
        snap.setPerms(perms);
        snap.setUserRoles(new HashMap<>(Map.of()));
        return snap;
    }

}
