package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;

/**
 * 快照仓储（端口）：一次性装载鉴权/投影全量快照的持久化唯一出入口
 * <p>
 * 组件内其余代码（投影存储/刷新）一律经本接口访问，**不得越过仓储直接调用 Mapper**；
 * 默认实现 {@link RbacSnapshotRepository}，宿主可自行实现本接口并注册 Bean 顶替。
 *
 * @author zwei
 */
public interface IRbacSnapshotRepository {

    /** 装载全量快照（角色/权限/权限组/关系），供投影与内存缓存使用 */
    RbacSnapshot loadAllForCache();

}
