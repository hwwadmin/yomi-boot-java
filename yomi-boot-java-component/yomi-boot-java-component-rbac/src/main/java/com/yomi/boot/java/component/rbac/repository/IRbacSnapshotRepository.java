package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;

/**
 * 快照仓储（端口）：一次性装载鉴权/投影全量快照的数据访问入口
 * <p>
 * 组件内其余代码（投影存储/刷新）都经本接口读写数据，Mapper 只由默认实现调用；
 * 默认实现 {@link RbacSnapshotRepository}，宿主可实现本接口并注册自己的 Bean。
 *
 * @author zwei
 */
public interface IRbacSnapshotRepository {

    /** 装载全量快照（角色/权限/权限组/关系），供投影与内存缓存使用 */
    RbacSnapshot loadAllForCache();

}
