package com.yomi.boot.java.component.event.repository;

import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.component.ds.MyBatisDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.event.entity.EventOutbox;
import com.yomi.boot.java.component.event.model.EventDlqQuery;
import com.yomi.boot.java.component.event.entity.EventOutboxDlq;
import com.yomi.boot.java.component.event.model.OutboxStatus;
import com.yomi.boot.java.component.event.repository.mapper.EventOutboxDlqMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 发件箱 DLQ 数据访问
 *
 * @author zwei
 */
public class EventOutboxDlqRepository extends MyBatisDao<EventOutboxDlqMapper, EventOutboxDlq>
        implements IEventOutboxDlqRepository {

    public EventOutboxDlqRepository(EventOutboxDlqMapper mapper) {
        super(mapper);
    }

    /** 插入 DLQ 行 */
    public void insert(EventOutboxDlq row) {
        row.init();
        save(row);
    }

    /** 按主键更新 DLQ 行 */
    public void updateRow(EventOutboxDlq row) {
        row.update();
        super.update(row);
    }

    /** 物理删除 DLQ 行 */
    public void deleteById(Long id) {
        del(id);
    }

    /**
     * 占用 DLQ 的投递租约（写入 {@code retry_after_time}）；成功则其他 worker 与扫描轮次在租约结束前取不到该行
     */
    public boolean tryClaimInFlight(Long dlqId, Duration lease) {
        Assert.nonNull(dlqId, "dlqId 不能为空");
        Instant now = Instant.now();
        Instant claimUntil = now.plus(lease);
        // 用 mapper.update(null, wrapper)：这里需要受影响行数判断是否占用成功，lambdaUpdate() 链只返回 boolean
        int rows = getMapper().update(null, new LambdaUpdateWrapper<EventOutboxDlq>()
                .set(EventOutboxDlq::getRetryAfterTime, claimUntil)
                .set(EventOutboxDlq::getUpdateTime, now)
                .eq(EventOutboxDlq::getId, dlqId)
                .isNull(EventOutboxDlq::getDeleteTime)
                .and(w -> w.isNull(EventOutboxDlq::getRetryAfterTime)
                        .or().le(EventOutboxDlq::getRetryAfterTime, now)));
        return rows == 1;
    }

    /** 按发件箱主键查 DLQ（uk_event_outbox_id） */
    public EventOutboxDlq findByEventOutboxId(Long eventOutboxId) {
        return lambdaQuery()
                .eq(EventOutboxDlq::getEventOutboxId, eventOutboxId)
                .isNull(EventOutboxDlq::getDeleteTime)
                .one();
    }

    /** 按主键查；不存在或已逻辑删除返回 null（与 {@code get()} 的"不存在抛异常"相区分，供重试前复核使用） */
    public EventOutboxDlq findById(Long id) {
        if (id == null) {
            return null;
        }
        EventOutboxDlq row = getMapper().selectById(id);
        return row != null && row.exist() ? row : null;
    }

    /**
     * 查询可重试的 DLQ 行（未达最大重试次数且已到重试窗口）
     * <p>由"未排期"与"已到期"两条各自走索引的查询合并，再按"就绪时间"排序：
     * {@code COALESCE(retry_after_time, create_time)} 这类排序表达式用不上索引，大表下每轮都要全表排序</p>
     */
    public List<EventOutboxDlq> fetchRetryable(String serviceName, int maxRetry, int limit, Instant now) {
        Assert.isTrue(limit > 0, "dlq limit 必须大于 0");
        if (maxRetry <= 0) {
            // maxRetry <= 0 时"未达重试上限"的条件恒不成立，直接不触库
            return List.of();
        }
        List<EventOutboxDlq> rows = new ArrayList<>(selectUnscheduled(serviceName, maxRetry, limit));
        int remain = limit - rows.size();
        if (remain > 0) {
            rows.addAll(selectDue(serviceName, maxRetry, now, remain));
        }
        rows.sort(Comparator.comparing(EventOutboxDlqRepository::readyTime));
        return rows;
    }

    /** 未排期（新建/人工复位）：按 create_time 有序，走 idx_service_name_create_time；上限由分页拦截器落到 LIMIT */
    private List<EventOutboxDlq> selectUnscheduled(String serviceName, int maxRetry, int limit) {
        return lambdaQuery()
                .eq(EventOutboxDlq::getServiceName, serviceName)
                .isNull(EventOutboxDlq::getDeleteTime)
                .lt(EventOutboxDlq::getRetryCount, maxRetry)
                .isNull(EventOutboxDlq::getRetryAfterTime)
                .orderByAsc(EventOutboxDlq::getCreateTime)
                .page(page(1, limit).setSearchCount(false))
                .getRecords();
    }

    /** 已到退避窗口：按 retry_after_time 有序，走 idx_retry_after_time；上限由分页拦截器落到 LIMIT */
    private List<EventOutboxDlq> selectDue(String serviceName, int maxRetry, Instant now, int limit) {
        return lambdaQuery()
                .eq(EventOutboxDlq::getServiceName, serviceName)
                .isNull(EventOutboxDlq::getDeleteTime)
                .lt(EventOutboxDlq::getRetryCount, maxRetry)
                .le(EventOutboxDlq::getRetryAfterTime, now)
                .orderByAsc(EventOutboxDlq::getRetryAfterTime)
                .orderByAsc(EventOutboxDlq::getCreateTime)
                .page(page(1, limit).setSearchCount(false))
                .getRecords();
    }

    /** 就绪时间：已排期取 retry_after_time，未排期取 create_time（对应 ORDER BY COALESCE 的语义） */
    private static Instant readyTime(EventOutboxDlq row) {
        if (row.getRetryAfterTime() != null) {
            return row.getRetryAfterTime();
        }
        return row.getCreateTime() == null ? Instant.MAX : row.getCreateTime();
    }

    /** 删除指定发件箱行的 DLQ（重发时移交重试责任） */
    public int deleteByEventOutboxId(Long eventOutboxId) {
        return getMapper().delete(new LambdaQueryWrapper<EventOutboxDlq>()
                .eq(EventOutboxDlq::getEventOutboxId, eventOutboxId));
    }

    /** 业务查询死信分页（创建时间倒序，走 idx_service_name_create_time 索引） */
    public Page<EventOutboxDlq> pageDlq(EventDlqQuery condition, String serviceName) {
        Page<EventOutboxDlq> page = new Page<>(
                Math.max(condition.getPageNo(), 1),
                Math.min(Math.max(condition.getPageSize(), 1), PageRequest.MAX_PAGE_SIZE));
        return getMapper().selectPage(page, new LambdaQueryWrapper<EventOutboxDlq>()
                .eq(EventOutboxDlq::getServiceName, serviceName)
                .ge(condition.getStartTime() != null, EventOutboxDlq::getCreateTime, condition.getStartTime())
                .le(condition.getEndTime() != null, EventOutboxDlq::getCreateTime, condition.getEndTime())
                .orderByDesc(EventOutboxDlq::getCreateTime)
                .orderByDesc(EventOutboxDlq::getId));
    }

    /** 将时间范围内 DLQ 的 retry_count 清零（运维批量复位） */
    public long resetRetryCountInRange(String serviceName, Instant from, Instant to) {
        Assert.nonNull(from, "from 不能为空");
        Assert.nonNull(to, "to 不能为空");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("结束时间不能早于开始时间");
        }
        return getMapper().resetRetryCountInRange(serviceName, from, to, Instant.now());
    }

    /**
     * 发件箱标为 SENT_SUCCESS 并删除 DLQ（须在事务内调用）
     */
    public void markOutboxSuccessAndDeleteDlq(EventOutbox outbox, EventOutboxDlq dlq, Instant sentTime,
                                              IEventOutboxRepository outboxRepository) {
        outbox.setStatus(OutboxStatus.SENT_SUCCESS);
        outbox.setSentTime(sentTime);
        outboxRepository.updateRow(outbox);
        deleteById(dlq.getId());
    }

}
