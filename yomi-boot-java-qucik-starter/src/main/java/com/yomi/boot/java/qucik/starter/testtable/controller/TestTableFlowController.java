package com.yomi.boot.java.qucik.starter.testtable.controller;

import com.yomi.boot.java.base.model.Response;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.qucik.starter.testtable.TestTable;
import com.yomi.boot.java.qucik.starter.testtable.repository.TestTableRepository;
import com.yomi.boot.java.qucik.starter.testtable.repository.mapper.TestTableXmlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 测试表流程接口：一次调用依次走 增 → 查 → 改 → 查 → 删 → 查 六步，逐步断言，用于验证
 * MyBatis-Plus 的封装与配置是否正常，任一步骤不符合预期即返回失败响应。
 * <p>
 * 两种实现各有一条流程（表结构见 yomi-boot-java-qucik-starter/sql/mysql|pgsql/test_table.sql）：
 * <ul>
 *     <li>GET /test/ds/test-table/dao：业务自建仓储形式（BaseMapper + Lambda 链式条件）</li>
 *     <li>GET /test/ds/test-table/xml：mapper.xml 形式（SQL 全部手写）</li>
 * </ul>
 * 每步都输出日志（前缀 {@code [测试表流程][dao|xml]}），收尾时统一再删一次记录，接口可重复调用
 *
 * @author zwei
 */
@Slf4j
@RestController
@RequestMapping("/test/ds/test-table")
@RequiredArgsConstructor
public class TestTableFlowController {

    /** createTime 与写入时刻的允许偏差：时区或类型映射有误时偏差会到小时级 */
    private static final Duration TIME_TOLERANCE = Duration.ofSeconds(60);

    /** 演示：自建仓储持久化流程标识 */
    private static final String FLOW_DAO = "dao";
    /** 演示：XML Mapper 流程标识 */
    private static final String FLOW_XML = "xml";

    /** 业务自建仓储（BaseMapper 形式） */
    private final TestTableRepository testTableRepository;

    /** mapper.xml 实现 */
    private final TestTableXmlMapper testTableXmlMapper;

    /**
     * 业务自建仓储形式：insert / requireById / update / query / delete 全链路
     */
    @GetMapping("/dao")
    public Response<List<String>> daoFlow() {
        List<String> steps = new ArrayList<>();
        String name = "mp-dao-" + IdUtils.nextId();
        log.info("[测试表流程][{}] 开始：增 → 查 → 改 → 查 → 删 → 查, name={}", FLOW_DAO, name);
        Long id = null;
        try {
            // 1. 增：mapper.insert
            Instant insertAt = Instant.now();
            TestTable entity = new TestTable().setName(name).setAge(18).setRemark("自建仓储流程：新增");
            testTableRepository.insert(entity);
            id = entity.getId();
            Assert.nonNull(id, "[1.新增] 未生成主键ID，雪花ID配置未生效");
            step(steps, FLOW_DAO, "1.新增: id=" + id + ", name=" + name + ", age=18, createTime=" + entity.getCreateTime());

            // 2. 查：selectById + 逻辑删除校验
            TestTable loaded = testTableRepository.requireById(id);
            Assert.isTrue(name.equals(loaded.getName()), "[2.查询] name不一致: 期望={}, 实际={}", name, loaded.getName());
            Assert.isTrue(Objects.equals(18, loaded.getAge()), "[2.查询] age不一致: 期望=18, 实际={}", loaded.getAge());
            Assert.isTrue("自建仓储流程：新增".equals(loaded.getRemark()), "[2.查询] remark不一致: 期望={}, 实际={}", "自建仓储流程：新增", loaded.getRemark());
            Assert.nonNull(loaded.getCreateTime(), "[2.查询] createTime为空，Instant 类型映射未生效");
            Instant createTime = loaded.getCreateTime();
            Assert.isTrue(withinTolerance(insertAt, createTime), "[2.查询] createTime与写入时刻偏差过大（时区或类型映射可能有误）: 写入={}, 查询={}", insertAt, createTime);
            step(steps, FLOW_DAO, "2.查询: 字段全部一致, createTime=" + createTime);

            // 3. 改：updateById
            String updatedName = name + "-u";
            loaded.setName(updatedName).setAge(20).setRemark("自建仓储流程：已更新");
            testTableRepository.update(loaded);
            step(steps, FLOW_DAO, "3.更新: name=" + updatedName + ", age=20");

            // 4. 查：lambda 链式条件查询，验证更新已落库且 createTime 未被改动
            TestTable reread = testTableRepository.query().eq(TestTable::getId, id).one();
            Assert.nonNull(reread, "[4.复查] lambdaQuery 未查询到记录, id={}", id);
            Assert.isTrue(updatedName.equals(reread.getName()), "[4.复查] 更新未生效: 期望={}, 实际={}", updatedName, reread.getName());
            Assert.isTrue(Objects.equals(20, reread.getAge()), "[4.复查] age更新未生效: 期望=20, 实际={}", reread.getAge());
            Assert.isTrue(createTime.equals(reread.getCreateTime()), "[4.复查] 更新改动了createTime: 更新前={}, 更新后={}", createTime, reread.getCreateTime());
            step(steps, FLOW_DAO, "4.复查: 更新已生效, createTime未被改动");

            // 5. 删：物理删除
            testTableRepository.delete(id);
            step(steps, FLOW_DAO, "5.删除: id=" + id);

            // 6. 查：确认删除生效
            TestTable afterDel = testTableRepository.findById(id);
            Assert.isNull(afterDel, "[6.复查] 删除后仍能查询到记录: {}", afterDel);
            step(steps, FLOW_DAO, "6.复查: 记录已不存在, 流程通过");

            log.info("[测试表流程][{}] 完成：六步全部通过, id={}", FLOW_DAO, id);
            return Response.success(steps);
        } catch (RuntimeException e) {
            log.error("[测试表流程][{}] 失败于第{}步: {}", FLOW_DAO, steps.size() + 1, StringUtils.normalizeSpace(e.getMessage()));
            throw e;
        } finally {
            Long cleanupId = id;
            if (cleanupId != null) {
                cleanupQuietly(() -> testTableRepository.delete(cleanupId));
            }
        }
    }

    /**
     * mapper.xml 形式：SQL 全部手写
     */
    @GetMapping("/xml")
    public Response<List<String>> xmlFlow() {
        List<String> steps = new ArrayList<>();
        String name = "mp-xml-" + IdUtils.nextId();
        log.info("[测试表流程][{}] 开始：增 → 查 → 改 → 查 → 删 → 查, name={}", FLOW_XML, name);

        // 1. 增：XML insert
        Instant insertAt = Instant.now();
        TestTable entity = new TestTable().setName(name).setAge(18).setRemark("mapper.xml流程：新增");
        entity.setId(IdUtils.nextId());
        entity.setCreateTime(insertAt);
        entity.setUpdateTime(insertAt);
        Long id = entity.getId();
        try {
            int inserted = testTableXmlMapper.insertTestTable(entity);
            Assert.isTrue(inserted == 1, "[1.新增] 影响行数异常: 期望=1, 实际={}", inserted);
            Assert.nonNull(id, "[1.新增] 未生成主键ID，雪花ID配置未生效");
            step(steps, FLOW_XML, "1.新增: id=" + id + ", name=" + name + ", age=18, createTime=" + entity.getCreateTime());

            // 2. 查：XML select
            TestTable loaded = testTableXmlMapper.selectTestTableById(id);
            Assert.nonNull(loaded, "[2.查询] 未查询到记录, id={}", id);
            Assert.isTrue(name.equals(loaded.getName()), "[2.查询] name不一致: 期望={}, 实际={}", name, loaded.getName());
            Assert.isTrue(Objects.equals(18, loaded.getAge()), "[2.查询] age不一致: 期望=18, 实际={}", loaded.getAge());
            Assert.isTrue("mapper.xml流程：新增".equals(loaded.getRemark()), "[2.查询] remark不一致: 期望={}, 实际={}", "mapper.xml流程：新增", loaded.getRemark());
            Assert.nonNull(loaded.getCreateTime(), "[2.查询] createTime为空，Instant 类型映射未生效（下划线转驼峰配置可能有误）");
            Instant createTime = loaded.getCreateTime();
            Assert.isTrue(withinTolerance(insertAt, createTime), "[2.查询] createTime与写入时刻偏差过大（时区或类型映射可能有误）: 写入={}, 查询={}", insertAt, createTime);
            step(steps, FLOW_XML, "2.查询: 字段全部一致, createTime=" + createTime);

            // 3. 改：XML update
            String updatedName = name + "-u";
            TestTable changed = new TestTable().setName(updatedName).setAge(20).setRemark("mapper.xml流程：已更新");
            changed.setId(id);
            changed.setUpdateTime(Instant.now());
            int updated = testTableXmlMapper.updateTestTable(changed);
            Assert.isTrue(updated == 1, "[3.更新] 影响行数异常: 期望=1, 实际={}", updated);
            step(steps, FLOW_XML, "3.更新: name=" + updatedName + ", age=20");

            // 4. 查：验证更新已落库且 createTime 未被改动
            TestTable reread = testTableXmlMapper.selectTestTableById(id);
            Assert.nonNull(reread, "[4.复查] 未查询到记录, id={}", id);
            Assert.isTrue(updatedName.equals(reread.getName()), "[4.复查] 更新未生效: 期望={}, 实际={}", updatedName, reread.getName());
            Assert.isTrue(Objects.equals(20, reread.getAge()), "[4.复查] age更新未生效: 期望=20, 实际={}", reread.getAge());
            Assert.isTrue(createTime.equals(reread.getCreateTime()), "[4.复查] 更新改动了createTime: 更新前={}, 更新后={}", createTime, reread.getCreateTime());
            step(steps, FLOW_XML, "4.复查: 更新已生效, createTime未被改动");

            // 5. 删：XML 物理删除
            int deleted = testTableXmlMapper.deleteTestTableById(id);
            Assert.isTrue(deleted == 1, "[5.删除] 影响行数异常: 期望=1, 实际={}", deleted);
            step(steps, FLOW_XML, "5.删除: id=" + id);

            // 6. 查：确认删除生效
            TestTable afterDel = testTableXmlMapper.selectTestTableById(id);
            Assert.isNull(afterDel, "[6.复查] 删除后仍能查询到记录: {}", afterDel);
            step(steps, FLOW_XML, "6.复查: 记录已不存在, 流程通过");

            log.info("[测试表流程][{}] 完成：六步全部通过, id={}", FLOW_XML, id);
            return Response.success(steps);
        } catch (RuntimeException e) {
            log.error("[测试表流程][{}] 失败于第{}步: {}", FLOW_XML, steps.size() + 1, StringUtils.normalizeSpace(e.getMessage()));
            throw e;
        } finally {
            cleanupQuietly(() -> testTableXmlMapper.deleteTestTableById(id));
        }
    }

    /**
     * 记录单个步骤：同时写入响应明细与日志
     */
    private static void step(List<String> steps, String flow, String detail) {
        steps.add(detail);
        log.info("[测试表流程][{}] {}", flow, detail);
    }

    /**
     * 清理测试数据，保证接口可重复调用；成功路径已在步骤5删除，
     * 这里再删一次，重复删除不影响结果
     */
    private static void cleanupQuietly(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Exception e) {
            log.warn("测试数据清理失败（不影响流程结论，可手工清理 test_table）", e);
        }
    }

    /** 判断两个 Instant 是否在允许偏差内 */
    private static boolean withinTolerance(Instant base, Instant target) {
        return Math.abs(Duration.between(base, target).toMillis()) <= TIME_TOLERANCE.toMillis();
    }

}
