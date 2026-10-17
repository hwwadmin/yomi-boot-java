package com.yomi.boot.java.component.rbac.cache;

import com.yomi.boot.java.base.utils.JsonUtils;
import com.yomi.boot.java.component.rbac.config.RbacProperties;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.entity.RbacPermGroup;
import com.yomi.boot.java.component.rbac.entity.RbacRole;
import com.yomi.boot.java.component.rbac.exception.RbacBiz;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.rbac.repository.IRbacSnapshotRepository;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import com.yomi.boot.java.component.redis.LockUtils;
import com.yomi.boot.java.component.redis.exception.LockBusyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RBAC 六表全量 Redis 投影 + 进程内 mem
 * <p>
 * 指针 miss 或读失败时回源 DB，取得刷新锁后写回 Redis（lazy warm）。
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RbacProjectionStore {

    /** 进程内快照缓存条目（快照 + 过期时间） */
    private record MemEntry(RbacSnapshot snap, long expireAtMillis) {
    }

    /** Redis 读写 */
    private final StringRedisTemplate redis;

    /** 投影键名 */
    private final RbacProjectionKeys keys;

    /** RBAC 配置（TTL 等） */
    private final RbacProperties properties;

    /** DB 全量快照加载 */
    private final IRbacSnapshotRepository snapshotRepository;

    /** 进程内快照及过期时间 */
    private final AtomicReference<MemEntry> mem = new AtomicReference<>();

    /** 保护 mem 读写的互斥锁 */
    private final ReentrantLock memLock = new ReentrantLock();

    /** 读鉴权快照：mem → Redis → DB（miss 时懒加载，取到锁后写回 Redis） */
    public RbacSnapshot loadSnapshot() {
        MemEntry entry = mem.get();
        long now = System.currentTimeMillis();
        if (entry != null && entry.snap != null && now < entry.expireAtMillis) {
            return entry.snap;
        }
        if (!memLock.tryLock()) {
            MemEntry stale = mem.get();
            if (stale != null && stale.snap != null) {
                return stale.snap;
            }
            return loadFromRedisOrSource();
        }
        try {
            entry = mem.get();
            now = System.currentTimeMillis();
            if (entry != null && entry.snap != null && now < entry.expireAtMillis) {
                return entry.snap;
            }
            RbacSnapshot snap = loadFromRedisOrSource();
            long ttlMs = properties.getMemTtl().toMillis();
            mem.set(new MemEntry(snap, System.currentTimeMillis() + ttlMs));
            return snap;
        } finally {
            memLock.unlock();
        }
    }

    /** 全量重建 Redis 投影；锁忙则抛 CACHE_REFRESH_BUSY */
    public void refresh() {
        String lockKey = keys.refreshLockKey();
        try {
            LockUtils.tryLock(lockKey, () -> {
                doRefresh();
                return null;
            }, "获取 RBAC 投影刷新锁失败");
        } catch (LockBusyException e) {
            throw RbacBiz.of(RbacErrorCode.CACHE_REFRESH_BUSY);
        }
    }

    /** 从 DB 全量加载并写入 Redis 新版本投影 */
    private RbacSnapshot doRefresh() {
        RbacSnapshot snap = snapshotRepository.loadAllForCache();
        String oldVersion = readCurrentVersion();
        String version = String.valueOf(System.nanoTime());
        Duration projectionTtl = properties.getProjectionTtl();
        writeVersion(version, snap, projectionTtl);
        RbacCachePointer pointer = new RbacCachePointer();
        pointer.setV(version);
        pointer.setN(countRows(snap));
        redis.opsForValue().set(keys.currentKey(), JsonUtils.toJson(pointer), projectionTtl);
        if (StringUtils.isNotBlank(oldVersion) && !oldVersion.equals(version)) {
            deleteVersion(oldVersion);
        }
        clearMem();
        return snap;
    }

    /** 清空进程内快照缓存 */
    private void clearMem() {
        memLock.lock();
        try {
            mem.set(null);
        } finally {
            memLock.unlock();
        }
    }

    /** 优先读 Redis 投影，miss 或异常时回源 DB */
    private RbacSnapshot loadFromRedisOrSource() {
        try {
            String raw = redis.opsForValue().get(keys.currentKey());
            if (StringUtils.isBlank(raw)) {
                return warmProjectionOnMiss("投影指针 miss");
            }
            RbacCachePointer pointer = JsonUtils.parse(raw, RbacCachePointer.class);
            if (pointer == null || StringUtils.isBlank(pointer.getV())) {
                return warmProjectionOnMiss("投影指针无效");
            }
            RbacSnapshot snap = readVersion(pointer.getV());
            if (!isVersionReadable(pointer.getV(), snap, pointer.getN())) {
                return warmProjectionOnMiss("投影版本不完整");
            }
            return snap;
        } catch (Exception e) {
            // Redis 不可用：读路径直接回源 DB（本次不写回）
            log.warn("[RBAC] Redis 投影读取失败，仅回源 DB（本次不写回）", e);
            return snapshotRepository.loadAllForCache();
        }
    }

    /**
     * 回源 DB；取到刷新锁则写回 Redis（与 {@link #refresh()} 同锁）。
     * <p>锁忙或 Redis 中途失败时只做 DB 回源：读路径的可用性不依赖 Redis，
     * 异常不会穿透成 403</p>
     */
    private RbacSnapshot warmProjectionOnMiss(String reason) {
        log.info("[RBAC] {}，回源并尝试写回 Redis", reason);
        String lockKey = keys.refreshLockKey();
        try {
            return LockUtils.tryLock(lockKey, () -> {
                String version = readCurrentVersion();
                if (StringUtils.isNotBlank(version)) {
                    RbacCachePointer pointer = readCurrentPointer();
                    if (pointer != null && StringUtils.isNotBlank(pointer.getV())) {
                        RbacSnapshot existing = readVersion(pointer.getV());
                        if (isVersionReadable(pointer.getV(), existing, pointer.getN())) {
                            return existing;
                        }
                    }
                }
                return doRefresh();
            }, "获取 RBAC 投影回源锁失败");
        } catch (LockBusyException e) {
            log.debug("[RBAC] 投影 warm 锁忙，本请求仅 DB 回源");
            return snapshotRepository.loadAllForCache();
        } catch (Exception e) {
            log.warn("[RBAC] 投影回源写回失败（可能 Redis 不可用），仅 DB 回源", e);
            return snapshotRepository.loadAllForCache();
        }
    }

    /** 读取当前投影指针 */
    private RbacCachePointer readCurrentPointer() {
        try {
            String raw = redis.opsForValue().get(keys.currentKey());
            if (StringUtils.isBlank(raw)) {
                return null;
            }
            return JsonUtils.parse(raw, RbacCachePointer.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取当前投影版本号 */
    private String readCurrentVersion() {
        RbacCachePointer pointer = readCurrentPointer();
        return pointer == null ? null : pointer.getV();
    }

    /**
     * 版本完整性校验：行数须与指针一致；指针声称有数据时至少存在一个 Hash。
     * 不完整版本视为不可读并触发回源（空库合法：n=0 且快照为空）。
     */
    private boolean isVersionReadable(String version, RbacSnapshot snap, int pointerCount) {
        if (!hasExpectedRowCount(snap, pointerCount)) {
            return false;
        }
        if (pointerCount <= 0) {
            return true;
        }
        return Boolean.TRUE.equals(redis.hasKey(keys.permHashKey(version)))
                || Boolean.TRUE.equals(redis.hasKey(keys.groupHashKey(version)))
                || Boolean.TRUE.equals(redis.hasKey(keys.roleHashKey(version)))
                || Boolean.TRUE.equals(redis.hasKey(keys.userRoleHashKey(version)))
                || Boolean.TRUE.equals(redis.hasKey(keys.roleGroupHashKey(version)))
                || Boolean.TRUE.equals(redis.hasKey(keys.groupPermHashKey(version)));
    }

    /** 行数校验（包级可见供单测）：指针 n=0 要求快照为空；n>0 要求读到行数完全一致 */
    static boolean hasExpectedRowCount(RbacSnapshot snap, int pointerCount) {
        if (snap == null) {
            return false;
        }
        return countRows(snap) == Math.max(pointerCount, 0);
    }

    /** 按版本号从 Redis Hash 组装快照 */
    private RbacSnapshot readVersion(String version) {
        RbacSnapshot snap = RbacSnapshot.empty();
        snap.setPerms(readEntityHash(keys.permHashKey(version), RbacPerm.class));
        snap.setGroups(readEntityHash(keys.groupHashKey(version), RbacPermGroup.class));
        snap.setRoles(readEntityHash(keys.roleHashKey(version), RbacRole.class));
        snap.setUserRoles(readIdListHash(keys.userRoleHashKey(version)));
        snap.setRoleGroups(readIdListHash(keys.roleGroupHashKey(version)));
        snap.setGroupPerms(readIdListHash(keys.groupPermHashKey(version)));
        return snap;
    }

    /** 读取实体列表 Hash */
    private <T> List<T> readEntityHash(String key, Class<T> type) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<T> out = new ArrayList<>(entries.size());
        for (Object value : entries.values()) {
            if (value == null) {
                continue;
            }
            T entity = JsonUtils.parse(String.valueOf(value), type);
            if (entity != null) {
                out.add(entity);
            }
        }
        return out;
    }

    /** 读取 ID 到 ID 列表的关系 Hash */
    private Map<Long, List<Long>> readIdListHash(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        Map<Long, List<Long>> out = new HashMap<>();
        if (entries == null) {
            return out;
        }
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            try {
                long id = Long.parseLong(String.valueOf(e.getKey()));
                List<Long> ids = JsonUtils.jsonStr2List(String.valueOf(e.getValue()), Long.class);
                out.put(id, ids == null ? List.of() : ids);
            } catch (Exception ignored) {
                // skip corrupt field
            }
        }
        return out;
    }

    /** 将快照六表写入指定版本的 Redis Hash */
    private void writeVersion(String version, RbacSnapshot snap, Duration ttl) {
        writeEntityHash(keys.permHashKey(version), snap.getPerms(), ttl);
        writeEntityHash(keys.groupHashKey(version), snap.getGroups(), ttl);
        writeEntityHash(keys.roleHashKey(version), snap.getRoles(), ttl);
        writeIdListHash(keys.userRoleHashKey(version), snap.getUserRoles(), ttl);
        writeIdListHash(keys.roleGroupHashKey(version), snap.getRoleGroups(), ttl);
        writeIdListHash(keys.groupPermHashKey(version), snap.getGroupPerms(), ttl);
    }

    /** 写入实体 JSON Hash 并设置 TTL */
    private void writeEntityHash(String key, List<?> entities, Duration ttl) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Map<String, String> fields = new HashMap<>();
        for (Object entity : entities) {
            if (entity instanceof RbacPerm p && p.getId() != null) {
                fields.put(String.valueOf(p.getId()), JsonUtils.toJson(p));
            } else if (entity instanceof RbacPermGroup g && g.getId() != null) {
                fields.put(String.valueOf(g.getId()), JsonUtils.toJson(g));
            } else if (entity instanceof RbacRole r && r.getId() != null) {
                fields.put(String.valueOf(r.getId()), JsonUtils.toJson(r));
            }
        }
        if (!fields.isEmpty()) {
            redis.opsForHash().putAll(key, fields);
            redis.expire(key, ttl);
        }
    }

    /** 写入关系列表 Hash 并设置 TTL */
    private void writeIdListHash(String key, Map<Long, List<Long>> src, Duration ttl) {
        if (src == null || src.isEmpty()) {
            return;
        }
        Map<String, String> fields = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : src.entrySet()) {
            fields.put(String.valueOf(e.getKey()), JsonUtils.toJson(e.getValue()));
        }
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);
    }

    /** 删除指定版本的全部 Hash 键 */
    private void deleteVersion(String version) {
        redis.delete(keys.permHashKey(version));
        redis.delete(keys.groupHashKey(version));
        redis.delete(keys.roleHashKey(version));
        redis.delete(keys.userRoleHashKey(version));
        redis.delete(keys.roleGroupHashKey(version));
        redis.delete(keys.groupPermHashKey(version));
    }

    /** 统计快照六表行数之和（用于指针校验） */
    private static int countRows(RbacSnapshot snap) {
        return snap.getPerms().size() + snap.getGroups().size() + snap.getRoles().size()
                + snap.getUserRoles().size() + snap.getRoleGroups().size() + snap.getGroupPerms().size();
    }

}
