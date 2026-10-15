package com.yomi.boot.java.component.rbac.repository.mapper;

import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 鉴权 SQL（启用链）：**仅供 {@link RbacAuthRepository} 使用**（其余代码一律经仓储端口 {@link IRbacAuthRepository} 访问，不直接调用）
 *
 * @author zwei
 */
@Mapper
public interface RbacAuthMapper {

    /** 用户是否绑定启用中的超管角色（启用链计数） */
    long countUserHasEnabledSuperAdmin(@Param("userId") long userId,
                                       @Param("superAdminCode") String superAdminCode,
                                       @Param("activeStatus") int activeStatus);

    /** 用户可用的 API 权限路径（启用链），供内存侧做精确/Ant 匹配 */
    List<String> listHttpAccessPaths(@Param("userId") long userId,
                                     @Param("method") String method,
                                     @Param("apiType") String apiType,
                                     @Param("activeStatus") int activeStatus);

    /** 用户可见的启用目录权限（启用链） */
    List<RbacPerm> listMenuByUserId(@Param("userId") long userId,
                                    @Param("menuType") String menuType,
                                    @Param("activeStatus") int activeStatus);

    /** 全部启用的目录权限（超管菜单列表用） */
    List<RbacPerm> listEnabledMenus(@Param("menuType") String menuType,
                                    @Param("activeStatus") int activeStatus);

    /** 持有启用超管角色的用户 ID 列表 */
    List<Long> listUserIdsWithEnabledSuperAdmin(@Param("superAdminCode") String superAdminCode,
                                                @Param("activeStatus") int activeStatus);

}
