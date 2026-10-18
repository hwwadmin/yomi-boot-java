package com.yomi.boot.java.component.rbac.config;

import com.yomi.boot.java.component.rbac.enums.RbacHttpPathStyle;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置前缀 {@code yomi.rbac}
 *
 * @author zwei
 */
@Data
@ConfigurationProperties(prefix = "yomi.rbac")
public class RbacProperties {

    /** 是否启用 RBAC 组件 */
    private boolean enabled = false;

    /** 是否使用 Redis 鉴权投影；false 时走 SQL */
    private boolean projectionEnabled = true;

    /** Redis 键前缀；空则按 yomi:rbac:{spring.application.name} 推导 */
    private String storePrefix;

    /** 进程内投影快照缓存时长 */
    private Duration memTtl = Duration.ofSeconds(10);

    /** Redis 投影版本 key TTL */
    private Duration projectionTtl = Duration.ofHours(2);

    /** 启动时是否全量刷新投影 */
    private boolean bootstrapRefresh = true;

    /** HTTP 鉴权使用的路径（与登记 API 权限时一致） */
    private RbacHttpPathStyle httpPathStyle = RbacHttpPathStyle.REQUEST_URI;

    /** 不做 RBAC 鉴权的路径（需与 token 放行路径对齐）；Spring PathPattern 语法，不能含首尾空格 */
    private List<String> skipAuthorizePaths = new ArrayList<>();

    /** 是否启用定时全量刷新投影（集群定时任务）；默认关闭 */
    private boolean scheduledRefreshEnabled = false;

    /** 定时刷新间隔；默认 60 秒 */
    private Duration scheduledRefreshInterval = Duration.ofSeconds(60);

    /** 启用时校验 TTL、定时刷新间隔、放行路径等 */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (memTtl == null || memTtl.isZero() || memTtl.isNegative()) {
            throw new IllegalStateException("yomi.rbac.mem-ttl 必须大于 0");
        }
        if (projectionTtl == null || projectionTtl.isZero() || projectionTtl.isNegative()) {
            throw new IllegalStateException("yomi.rbac.projection-ttl 必须大于 0");
        }
        // 首尾空格型笔误 Spring 会照原样注册且永不匹配：放行路径失效，全量请求都会 403，这里在启动期拦截
        for (String skipPath : skipAuthorizePaths) {
            if (skipPath == null || !skipPath.equals(skipPath.trim())) {
                throw new IllegalStateException("yomi.rbac.skip-authorize-paths 不能为空或含首尾空格: [" + skipPath + "]");
            }
        }
        if (!scheduledRefreshEnabled) {
            return;
        }
        if (scheduledRefreshInterval == null || scheduledRefreshInterval.isZero() || scheduledRefreshInterval.isNegative()) {
            throw new IllegalStateException("yomi.rbac.scheduled-refresh-interval 必须大于 0");
        }
    }

}
