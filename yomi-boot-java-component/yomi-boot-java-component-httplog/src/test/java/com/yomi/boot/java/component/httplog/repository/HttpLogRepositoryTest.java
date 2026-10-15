package com.yomi.boot.java.component.httplog.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageRequest;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;
import com.yomi.boot.java.component.httplog.repository.mapper.HttpLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储测试：条件组装（概要列/参数/方法归一/分页收敛）、批量会话、两阶段删除（假 Mapper/假会话）
 *
 * @author zwei
 */
class HttpLogRepositoryTest {

    /** 纯测试环境无 Mapper 初始化，需手工注册实体元数据（lambda 列解析依赖） */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), HttpLogEntity.class);
    }

    @Test
    void pageSummary_buildsConditionsAndSummaryColumns() {
        CapturingMapper capturing = new CapturingMapper();
        capturing.rows = List.of(entity(7L));
        capturing.total = 1;
        HttpLogRepository repository = new HttpLogRepository(capturing.mapper(), null);

        HttpLogQuery query = new HttpLogQuery();
        query.setPageNo(0);
        query.setPageSize(10_000);
        query.setMethod("get");
        query.setUriLike("/api");
        query.setMinStatus(500);
        Page<HttpLogEntity> page = repository.pageSummary(query, "svc-a");

        // 分页收敛
        assertEquals(1, page.getCurrent());
        assertEquals(PageRequest.MAX_PAGE_SIZE, page.getSize());
        // 概要瘦查询：不选 body/headers 大字段
        String select = capturing.wrapper.getSqlSelect();
        assertTrue(select.contains("id"), select);
        assertTrue(select.contains("request_time"), select);
        assertFalse(select.contains("request_body"), select);
        assertFalse(select.contains("response_headers"), select);
        // 条件与排序：时间条件/排序按 create_time（与 request_time 同值、走既有索引）
        String sql = capturing.wrapper.getTargetSql();
        assertTrue(sql.contains("service_name"), sql);
        assertTrue(sql.contains("uri"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("create_time"), sql);
        // 参数：服务过滤入参、method 归一为大写、minStatus
        var params = capturing.wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains("svc-a"), String.valueOf(params));
        assertTrue(params.contains("GET"), String.valueOf(params));
        assertTrue(params.contains(500), String.valueOf(params));
    }

    @Test
    void pageSummary_nullServiceFilter_skipsServiceCondition() {
        CapturingMapper capturing = new CapturingMapper();
        HttpLogRepository repository = new HttpLogRepository(capturing.mapper(), null);

        repository.pageSummary(new HttpLogQuery(), null);

        // 先触发一次渲染（参数映射惰性构建），否则空 map 会假绿
        capturing.wrapper.getTargetSql();
        assertFalse(capturing.wrapper.getParamNameValuePairs().containsValue("svc-a"));
        assertFalse(capturing.wrapper.getParamNameValuePairs().containsValue("test"));
    }

    @Test
    void insertBatch_usesBatchSessionAndCommits() {
        AtomicInteger inserts = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        HttpLogRepository repository = new HttpLogRepository(null,
                batchSessionFactory(inserts, commits, closed));

        repository.insertBatch(List.of(entity(1L), entity(2L)));

        assertEquals(2, inserts.get());
        assertEquals(1, commits.get());
        assertEquals(1, closed.get());
    }

    @Test
    void selectExpiredIds_andDeleteByIds() {
        CapturingMapper capturing = new CapturingMapper();
        capturing.rows = List.of(entity(1L), entity(2L));
        capturing.deleteRows = 2;
        HttpLogRepository repository = new HttpLogRepository(capturing.mapper(), null);

        List<Long> ids = repository.selectExpiredIds("svc", Instant.now(), 100);
        assertEquals(List.of(1L, 2L), ids);
        assertEquals(2, repository.deleteByIds(ids));
    }

    private static HttpLogEntity entity(long id) {
        HttpLogEntity entity = new HttpLogEntity();
        entity.setId(id);
        entity.setServiceName("svc-a");
        entity.setMethod("GET");
        entity.setUri("/demo");
        return entity;
    }

    /** 假会话工厂：openSession 返回可计数 insert/commit/close 的批量会话 */
    private static SqlSessionFactory batchSessionFactory(AtomicInteger inserts, AtomicInteger commits,
                                                         AtomicInteger closed) {
        HttpLogMapper countingMapper = (HttpLogMapper) Proxy.newProxyInstance(
                HttpLogMapper.class.getClassLoader(), new Class<?>[]{HttpLogMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        inserts.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                });
        SqlSession session = (SqlSession) Proxy.newProxyInstance(
                SqlSession.class.getClassLoader(), new Class<?>[]{SqlSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMapper" -> countingMapper;
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "close" -> {
                        closed.incrementAndGet();
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        return (SqlSessionFactory) Proxy.newProxyInstance(
                SqlSessionFactory.class.getClassLoader(), new Class<?>[]{SqlSessionFactory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "openSession" -> session;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        return null;
    }

    /** 假 Mapper：捕获分页参数与条件，返回预设数据 */
    static class CapturingMapper {

        Page<HttpLogEntity> page;

        LambdaQueryWrapper<HttpLogEntity> wrapper;

        List<HttpLogEntity> rows = List.of();

        long total;

        int deleteRows;

        @SuppressWarnings("unchecked")
        HttpLogMapper mapper() {
            return (HttpLogMapper) Proxy.newProxyInstance(HttpLogMapper.class.getClassLoader(),
                    new Class<?>[]{HttpLogMapper.class}, (proxy, method, args) -> {
                        if ("selectPage".equals(method.getName())) {
                            page = (Page<HttpLogEntity>) args[0];
                            wrapper = (LambdaQueryWrapper<HttpLogEntity>) args[1];
                            page.setRecords(rows);
                            page.setTotal(total);
                            return page;
                        }
                        if ("delete".equals(method.getName())) {
                            return deleteRows;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

    }

}
