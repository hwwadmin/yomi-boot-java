package com.yomi.boot.java.component.rbac.eval;

import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 按快照实例身份缓存 id 索引（{@link RbacAuthEvaluator.EvalIndex}）。
 * <p>投影命中路径上同一快照实例会被复用（memTtl 窗口），此前每请求都要遍历全量角色/权限组/权限重建索引；
 * 这里以"快照换了就重建"为不变量：同实例复用、不同实例自动重建，并发最坏只是多建一次</p>
 *
 * @author zwei
 */
public class RbacAuthIndexCache {

    /** 用户→授权索引的本地缓存 */
    private final AtomicReference<Entry> cache = new AtomicReference<>();

    /** 为给定快照返回 EvalIndex；同一快照实例复用缓存，快照更换则重建 */
    public RbacAuthEvaluator.EvalIndex indexFor(RbacSnapshot snapshot) {
        Entry entry = cache.get();
        if (entry != null && entry.snapshot() == snapshot) {
            return entry.index();
        }
        RbacAuthEvaluator.EvalIndex built = RbacAuthEvaluator.buildIndex(snapshot);
        cache.set(new Entry(snapshot, built));
        return built;
    }

    /** 内存索引缓存条目 */
    private record Entry(RbacSnapshot snapshot, RbacAuthEvaluator.EvalIndex index) {
    }

}
