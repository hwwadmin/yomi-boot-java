package com.yomi.boot.java.qucik.starter.testtable.repository;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.IdUtils;
import com.yomi.boot.java.qucik.starter.testtable.TestTable;
import com.yomi.boot.java.qucik.starter.testtable.repository.mapper.TestTableMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 测试表仓储（业务自建：直接持有 Mapper）
 *
 * @author zwei
 */
@Repository
public class TestTableRepository {

    private final TestTableMapper mapper;

    public TestTableRepository(TestTableMapper mapper) {
        this.mapper = mapper;
    }

    /** lambda 链式条件 */
    public LambdaQueryChainWrapper<TestTable> query() {
        return ChainWrappers.lambdaQueryChain(mapper, TestTable.class);
    }

    /** 新增 */
    public void insert(TestTable row) {
        Assert.nonNull(row, "table#TestTable不能为空");
        Instant now = Instant.now();
        if (row.getId() == null) {
            row.setId(IdUtils.nextId());
        }
        if (row.getCreateTime() == null) {
            row.setCreateTime(now);
        }
        row.setUpdateTime(now);
        mapper.insert(row);
    }

    /** 按主键查；不存在或已删除抛错 */
    public TestTable requireById(Long id) {
        Assert.nonNull(id, "table#TestTable ID不能为空");
        TestTable entity = mapper.selectById(id);
        Assert.nonNull(entity, "table#TestTable不存在");
        Assert.isTrue(entity.getDeleteTime() == null, "table#TestTable已删除");
        return entity;
    }

    /** 按主键查原始行 */
    public TestTable findById(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    /** 按主键更新 */
    public void update(TestTable row) {
        Assert.nonNull(row, "table#TestTable不能为空");
        Assert.nonNull(row.getId(), "table#TestTable ID不能为空");
        row.setUpdateTime(Instant.now());
        int rows = mapper.updateById(row);
        Assert.isTrue(rows == 1, "table#TestTable更新失败，ID[{}]不存在", row.getId());
    }

    /** 按主键物理删除 */
    public void delete(Long id) {
        Assert.nonNull(id, "table#TestTable ID不能为空");
        mapper.deleteById(id);
    }

}
