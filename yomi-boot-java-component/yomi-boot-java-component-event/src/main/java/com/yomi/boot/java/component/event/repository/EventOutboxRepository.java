package com.yomi.boot.java.component.event.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.model.EventOutboxQuery;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 发件箱数据访问
 *
 * @author zwei
 */
public class EventOutboxRepository extends MyBatisDao<EventOutboxMapper, EventOutbox>
        implements IEventOutboxRepository {

    public EventOutboxRepository(EventOutboxMapper mapper) {
        super(mapper);
    }

    /** 插入待发件箱行（初始化审计字段） */
    public void insert(EventOutbox row) {
        row.init();
        save(row);
    }

    /** 查询 PENDING 行（不改状态）；批量上限由分页拦截器落到 SQL 的 LIMIT（缺拦截器由 ds 自检拦在启动期） */
    public List<EventOutbox> fetchPending(String serviceName, int limit) {
        Assert.isTrue(limit > 0, "fetch limit 必须大于 0");
        return lambdaQuery()
                .eq(EventOutbox::getServiceName, serviceName)
                .eq(EventOutbox::getStatus, OutboxStatus.PENDING)
                .isNull(EventOutbox::getDeleteTime)
                .orderByAsc(EventOutbox::getCreateTime)
                // 只取一页且不要 count：等价 LIMIT n 的单条查询
                .page(page(1, limit).setSearchCount(false))
                .getRecords();
    }

    /**
     * 查询 PENDING 并批量标记 FETCHED；并发下可能部分行已被其他实例抓取
     */
    public List<EventOutbox> fetchAndMark(String serviceName, int limit) {
        List<EventOutbox> pending = fetchPending(serviceName, limit);
        if (pending.isEmpty()) {
            return pending;
        }
        List<Long> ids = pending.stream().map(EventOutbox::getId).toList();
        // 与 fetch_time 列（datetime(3)）对齐到毫秒，否则写库值被截断后按归属重查匹配不上
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // 用 mapper.update(null, wrapper) 而非 lambdaUpdate() 链：后者只返回 boolean，这里要受影响行数判抢占
        int updated = getMapper().update(null, new LambdaUpdateWrapper<EventOutbox>()
                .set(EventOutbox::getStatus, OutboxStatus.FETCHED)
                .set(EventOutbox::getFetchTime, now)
                .set(EventOutbox::getUpdateTime, now)
                .eq(EventOutbox::getStatus, OutboxStatus.PENDING)
                .isNull(EventOutbox::getDeleteTime)
                .in(EventOutbox::getId, ids));
        if (updated == 0) {
            return List.of();
        }
        if (updated < ids.size()) {
            // 部分行已被其他实例抢占：本实例写入的归属标识就是 fetch_time = now，
            // 只按 status 重查会把他人已抢占（可能正在投递）的行也算作自己的，导致重复发送
            pending = lambdaQuery()
                    .in(EventOutbox::getId, ids)
                    .eq(EventOutbox::getStatus, OutboxStatus.FETCHED)
                    .eq(EventOutbox::getFetchTime, now)
                    .isNull(EventOutbox::getDeleteTime)
                    .list();
        } else {
            for (EventOutbox row : pending) {
                row.setStatus(OutboxStatus.FETCHED);
                row.setFetchTime(now);
            }
        }
        return pending;
    }

    /** 查询 FETCHED 超时未投递的行 */
    public List<EventOutbox> findFetchedTimeout(String serviceName, Instant fetchTimeBefore) {
        return lambdaQuery()
                .eq(EventOutbox::getServiceName, serviceName)
                .eq(EventOutbox::getStatus, OutboxStatus.FETCHED)
                .lt(EventOutbox::getFetchTime, fetchTimeBefore)
                .isNull(EventOutbox::getDeleteTime)
                .list();
    }

    /** 将 FETCHED 批量重置为 PENDING（投递超时回收） */
    public void resetFetchedBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        lambdaUpdate()
                .set(EventOutbox::getStatus, OutboxStatus.PENDING)
                .set(EventOutbox::getFetchTime, null)
                .set(EventOutbox::getUpdateTime, Instant.now())
                .eq(EventOutbox::getStatus, OutboxStatus.FETCHED)
                .isNull(EventOutbox::getDeleteTime)
                .in(EventOutbox::getId, ids)
                .update();
    }

    /** 按主键更新并刷新 {@link EventOutbox#update()} */
    public void updateRow(EventOutbox row) {
        row.update();
        super.update(row);
    }

    /** 按主键批量查询未删除行 */
    public List<EventOutbox> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return lambdaQuery()
                .in(EventOutbox::getId, ids)
                .isNull(EventOutbox::getDeleteTime)
                .list();
    }

    /** 按服务名 + 事件ID 查发件箱行（过滤逻辑删除）；不存在返回 null */
    public EventOutbox findByEventId(String serviceName, String eventId) {
        return lambdaQuery()
                .eq(EventOutbox::getServiceName, serviceName)
                .eq(EventOutbox::getEventId, eventId)
                .isNull(EventOutbox::getDeleteTime)
                .one();
    }

    /** 按主键查；不存在或已逻辑删除返回 null（与 {@code get()} 的"不存在抛异常"相区分，供指针失效路径使用） */
    public EventOutbox findById(Long id) {
        if (id == null) {
            return null;
        }
        EventOutbox row = getMapper().selectById(id);
        return row != null && row.exist() ? row : null;
    }

    /** 重发：未送达（非 SENT_SUCCESS）的行重置为 PENDING 并清 fetch_time；返回受影响行数 */
    public int resetToPending(String serviceName, String eventId) {
        return getMapper().update(null, new LambdaUpdateWrapper<EventOutbox>()
                .set(EventOutbox::getStatus, OutboxStatus.PENDING)
                .set(EventOutbox::getFetchTime, null)
                .set(EventOutbox::getUpdateTime, Instant.now())
                .eq(EventOutbox::getServiceName, serviceName)
                .eq(EventOutbox::getEventId, eventId)
                .isNull(EventOutbox::getDeleteTime)
                .ne(EventOutbox::getStatus, OutboxStatus.SENT_SUCCESS));
    }

    /** 业务查询分页（概要列不含 payload；创建时间倒序，走 idx_service_status_create_time 索引） */
    public Page<EventOutbox> pageOutbox(EventOutboxQuery condition, String serviceName) {
        Page<EventOutbox> page = new Page<>(
                Math.max(condition.getPageNo(), 1),
                Math.min(Math.max(condition.getPageSize(), 1), PageRequest.MAX_PAGE_SIZE));
        return getMapper().selectPage(page, new LambdaQueryWrapper<EventOutbox>()
                .select(EventOutbox::getId, EventOutbox::getEventId, EventOutbox::getTopic,
                        EventOutbox::getStatus, EventOutbox::getFetchTime, EventOutbox::getSentTime,
                        EventOutbox::getCreateTime)
                .eq(EventOutbox::getServiceName, serviceName)
                .eq(condition.getStatus() != null, EventOutbox::getStatus, condition.getStatus())
                .eq(condition.getTopic() != null && !condition.getTopic().isBlank(),
                        EventOutbox::getTopic, condition.getTopic())
                .ge(condition.getStartTime() != null, EventOutbox::getCreateTime, condition.getStartTime())
                .le(condition.getEndTime() != null, EventOutbox::getCreateTime, condition.getEndTime())
                .isNull(EventOutbox::getDeleteTime)
                .orderByDesc(EventOutbox::getCreateTime)
                .orderByDesc(EventOutbox::getId));
    }

    /** 保留期清理：物理删除指定服务已成功且发送时间早于阈值的行 */
    public int deleteSentSuccessBefore(String serviceName, Instant sentTimeBefore, int limit) {
        Assert.isTrue(serviceName != null && !serviceName.isBlank(), "serviceName 不能为空");
        Assert.isTrue(limit > 0, "清理 limit 必须大于 0");
        Assert.nonNull(sentTimeBefore, "清理时间阈值不能为空");
        return getMapper().deleteSentSuccessBefore(serviceName, OutboxStatus.SENT_SUCCESS, sentTimeBefore, limit);
    }

}
