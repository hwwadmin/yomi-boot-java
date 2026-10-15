package com.yomi.boot.java.qucik.starter.testtable.repository;

import com.yomi.boot.java.component.ds.MyBatisDao;
import com.yomi.boot.java.qucik.starter.testtable.TestTable;
import com.yomi.boot.java.qucik.starter.testtable.repository.mapper.TestTableMapper;
import org.springframework.stereotype.Repository;

/**
 * 测试表 DAO（MyBatis-Plus 封装形式）
 *
 * @author zwei
 */
@Repository
public class TestTableRepository extends MyBatisDao<TestTableMapper, TestTable> {

    public TestTableRepository(TestTableMapper mapper) {
        super(mapper);
    }

}
