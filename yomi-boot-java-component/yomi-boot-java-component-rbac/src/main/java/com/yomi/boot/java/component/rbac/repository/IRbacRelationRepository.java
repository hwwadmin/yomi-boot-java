package com.yomi.boot.java.component.rbac.repository;

import com.yomi.boot.java.component.rbac.entity.RbacRole;

import java.util.List;
import java.util.Map;

/**
 * 关系仓储（端口）：用户-角色 / 角色-权限组 / 权限组-权限 三类关系的数据访问入口
 * <p>
 * 组件内其余代码（管理服务/快照/投影）都经本接口读写数据，Mapper 只由默认实现调用；
 * 默认实现 {@link RbacRelationRepository}，宿主可实现本接口并注册自己的 Bean。
 * <p>
 * 写方法（{@code replace*} / {@code deleteRoleRelations}）语义是原子的：默认实现整类带 REQUIRED 事务，
 * 调用方已有事务时汇入，没有也能自成事务，绑定替换要么全部生效要么全部回滚；
 * 自行实现本接口的宿主需自行保证同样的原子性。
 *
 * @author zwei
 */
public interface IRbacRelationRepository {

    /** 用户的角色 ID 列表 */
    List<Long> listRoleIdsByUserId(long userId);

    /** 角色的权限组 ID 列表 */
    List<Long> listGroupIdsByRoleId(long roleId);

    /** 权限组的权限 ID 列表 */
    List<Long> listPermIdsByGroupId(long groupId);

    /** 全量替换用户角色绑定 */
    void replaceUserRoles(long userId, List<Long> roleIds, long actorId);

    /** 全量替换角色权限组绑定 */
    void replaceRoleGroups(long roleId, List<Long> groupIds, long actorId);

    /** 全量替换权限组权限绑定 */
    void replaceGroupPerms(long groupId, List<Long> permIds, long actorId);

    /** 删除角色的全部关系（角色-组、用户-角色） */
    void deleteRoleRelations(long roleId);

    /** 批量查用户的角色（用户ID → 角色列表） */
    Map<Long, List<RbacRole>> listRolesByUserIds(List<Long> userIds);

}
