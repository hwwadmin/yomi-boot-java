package com.yomi.boot.java.component.rbac.core;

import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.cache.RbacProjectionStore;
import com.yomi.boot.java.component.rbac.config.RbacProperties;
import com.yomi.boot.java.component.rbac.convert.RbacModelConverter;
import com.yomi.boot.java.component.rbac.entity.RbacPerm;
import com.yomi.boot.java.component.rbac.eval.RbacAuthEvaluator;
import com.yomi.boot.java.component.rbac.eval.RbacAuthIndexCache;
import com.yomi.boot.java.component.rbac.exception.RbacAuthException;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.rbac.model.RbacPermModel;
import com.yomi.boot.java.component.rbac.repository.IRbacAuthRepository;
import com.yomi.boot.java.component.rbac.snapshot.RbacSnapshot;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 授权查询实现
 *
 * @author zwei
 */
@RequiredArgsConstructor
public class RbacAuthService implements IRbacAuthService {

    /** DB 直查授权（未开投影时） */
    private final IRbacAuthRepository authRepository;

    /** 是否启用 Redis 投影等 */
    private final RbacProperties properties;

    /** Redis/内存鉴权投影 */
    private final RbacProjectionStore projectionStore;

    /** 按快照实例缓存权限索引，避免同一快照重复构建 */
    private final RbacAuthIndexCache indexCache = new RbacAuthIndexCache();

    /** 判断用户对 method+path 是否有 API 权限 */
    @Override
    public boolean canAccessHttp(long userId, String method, String path) {
        if (userId <= 0) {
            return false;
        }
        method = StringUtils.upperCase(StringUtils.trimToEmpty(method));
        path = StringUtils.trimToEmpty(path);
        try {
            if (properties.isProjectionEnabled() && projectionStore != null) {
                RbacSnapshot snap = projectionStore.loadSnapshot();
                return RbacAuthEvaluator.canAccessHttp(snap, indexCache.indexFor(snap), userId, method, path);
            }
            if (authRepository.userHasEnabledSuperAdmin(userId)) {
                return true;
            }
            if (method.isEmpty() || path.isEmpty()) {
                return false;
            }
            return authRepository.existsHttpAccess(userId, method, path);
        } catch (RbacAuthException e) {
            throw e;
        } catch (Exception e) {
            throw RbacAuthException.of(RbacErrorCode.EVAL_FAILED, null, e);
        }
    }

    /** 返回用户可见的 UI 菜单树 */
    @Override
    public List<RbacPermModel> listUiMenus(long userId) {
        if (userId <= 0) {
            return List.of();
        }
        try {
            if (properties.isProjectionEnabled() && projectionStore != null) {
                RbacSnapshot snap = projectionStore.loadSnapshot();
                return RbacAuthEvaluator.listUiMenus(snap, indexCache.indexFor(snap), userId).stream()
                        .map(RbacModelConverter::toPermModel).toList();
            }
            if (authRepository.userHasEnabledSuperAdmin(userId)) {
                return authRepository.listEnabledMenus().stream().map(RbacModelConverter::toPermModel).toList();
            }
            return authRepository.listMenuByUserId(userId).stream().map(RbacModelConverter::toPermModel).toList();
        } catch (RbacAuthException e) {
            throw e;
        } catch (Exception e) {
            throw RbacAuthException.of(RbacErrorCode.EVAL_FAILED, null, e);
        }
    }

    /** 从库重建并写入 Redis 授权投影 */
    @Override
    public void refreshProjection() {
        if (!properties.isProjectionEnabled() || projectionStore == null) {
            return;
        }
        projectionStore.refresh();
    }

}
