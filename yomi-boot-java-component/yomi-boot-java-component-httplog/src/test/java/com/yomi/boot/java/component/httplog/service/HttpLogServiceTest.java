package com.yomi.boot.java.component.httplog.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yomi.boot.java.base.model.PageResponse;
import com.yomi.boot.java.component.httplog.config.HttpLogProperties;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import com.yomi.boot.java.component.httplog.model.HttpLogModel;
import com.yomi.boot.java.component.httplog.model.HttpLogQuery;
import com.yomi.boot.java.component.httplog.repository.IHttpLogRepository;
import com.yomi.boot.java.component.httplog.support.HttpLogKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 查询服务测试：服务维度业务规则（默认限当前服务/显式优先/allServices 放开）与实体到视图映射
 *
 * @author zwei
 */
class HttpLogServiceTest {

    @Test
    void page_defaultsToCurrentService() {
        CapturingRepository repository = new CapturingRepository();
        HttpLogService service = new HttpLogService(repository, keys());

        service.page(null);

        assertEquals("test", repository.lastServiceFilter);
    }

    @Test
    void page_explicitServiceName_winsOverCurrent() {
        CapturingRepository repository = new CapturingRepository();
        HttpLogService service = new HttpLogService(repository, keys());

        HttpLogQuery query = new HttpLogQuery();
        query.setServiceName("svc-x");
        service.page(query);

        assertEquals("svc-x", repository.lastServiceFilter);
    }

    @Test
    void page_allServices_passesNullFilter() {
        CapturingRepository repository = new CapturingRepository();
        HttpLogService service = new HttpLogService(repository, keys());

        HttpLogQuery query = new HttpLogQuery();
        query.setAllServices(true);
        service.page(query);

        assertNull(repository.lastServiceFilter);
    }

    @Test
    void page_mapsEntitiesToModels() {
        CapturingRepository repository = new CapturingRepository();
        repository.rows = List.of(entity(7L));
        repository.total = 1;
        HttpLogService service = new HttpLogService(repository, keys());

        PageResponse<HttpLogModel> response = service.page(new HttpLogQuery());

        assertEquals(1, response.getData().size());
        assertEquals(7L, response.getData().get(0).getId());
        assertEquals("GET", response.getData().get(0).getMethod());
        assertEquals(1L, response.getTotal());
    }

    @Test
    void get_nullIdOrMissing_returnsNull() {
        CapturingRepository repository = new CapturingRepository();
        HttpLogService service = new HttpLogService(repository, keys());

        assertNull(service.get(null));
        assertNull(service.get(9L));
    }

    @Test
    void get_mapsFullFields() {
        CapturingRepository repository = new CapturingRepository();
        HttpLogEntity entity = entity(7L);
        entity.setRequestBody("{\"a\":1}");
        repository.single = entity;
        HttpLogService service = new HttpLogService(repository, keys());

        HttpLogModel model = service.get(7L);

        assertEquals("{\"a\":1}", model.getRequestBody());
        assertEquals("/demo", model.getUri());
    }

    @Test
    void get_otherServiceEntity_returnsModel() {
        // 行为锁定：按 id 查询不做服务隔离（多业务日志聚合一表、后台统一查看是主场景）
        CapturingRepository repository = new CapturingRepository();
        HttpLogEntity entity = entity(7L);
        entity.setServiceName("other-service");
        repository.single = entity;
        HttpLogService service = new HttpLogService(repository, keys());

        HttpLogModel model = service.get(7L);

        assertEquals(7L, model.getId());
        assertEquals("other-service", model.getServiceName());
    }

    private static HttpLogEntity entity(long id) {
        HttpLogEntity entity = new HttpLogEntity();
        entity.setId(id);
        entity.setServiceName("test");
        entity.setMethod("GET");
        entity.setUri("/demo");
        return entity;
    }

    private static HttpLogKeys keys() {
        return new HttpLogKeys(new HttpLogProperties(),
                new MockEnvironment().withProperty("spring.application.name", "test"));
    }

    /** 假仓储：实现端口（本用例只触发 pageSummary/findById，其余为空实现） */
    static class CapturingRepository implements IHttpLogRepository {

        String lastServiceFilter;

        List<HttpLogEntity> rows = List.of();

        long total;

        HttpLogEntity single;

        @Override
        public Page<HttpLogEntity> pageSummary(HttpLogQuery condition, String serviceFilter) {
            this.lastServiceFilter = serviceFilter;
            Page<HttpLogEntity> page = new Page<>(1, 10);
            page.setRecords(rows);
            page.setTotal(total);
            return page;
        }

        @Override
        public HttpLogEntity findById(Long id) {
            return single;
        }

        @Override
        public void insertBatch(List<HttpLogEntity> entities) {
        }

        @Override
        public void insert(HttpLogEntity entity) {
        }

        @Override
        public int deleteExpiredLimited(String serviceName, Instant createTimeBefore, int limit) {
            return 0;
        }

        @Override
        public Instant findOldestCreateTime(String serviceName) {
            return null;
        }

    }

}
