package com.yomi.boot.java.component.rbac.relay;

import com.yomi.boot.java.base.exception.BizException;
import com.yomi.boot.java.component.rbac.IRbacAuthService;
import com.yomi.boot.java.component.rbac.exception.RbacErrorCode;
import com.yomi.boot.java.component.roundrobin.RoundRobinContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时全量刷新鉴权 Redis 投影
 * <p>
 * 需 {@code yomi.rbac.scheduled-refresh-enabled=true} 且 {@code yomi.roundrobin.enabled=true}
 *
 * @author zwei
 */
@Slf4j
@RequiredArgsConstructor
public class RbacProjectionRefresher {

    /** RoundRobin 任务名；全局任务（整投影刷新），不带服务名后缀 */
    public static final String TASK_NAME = "rbacProjectionRefresh";

    /** 触发投影刷新的授权服务 */
    private final IRbacAuthService authService;

    /** 定时任务入口：全量刷新鉴权投影；投影刷新进行中时跳过本次 */
    public void refresh(RoundRobinContext ctx) {
        try {
            authService.refreshProjection();
        } catch (BizException e) {
            if (RbacErrorCode.CACHE_REFRESH_BUSY.getCode().equals(e.getErrorCode())) {
                log.debug("[RBAC] 定时刷新跳过：投影刷新进行中");
                return;
            }
            log.warn("[RBAC] 定时刷新投影失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[RBAC] 定时刷新投影失败", e);
        }
    }

}
