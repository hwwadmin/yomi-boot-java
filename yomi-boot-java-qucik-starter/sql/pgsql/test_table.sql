-- PostgreSQL 建表脚本（MySQL 版见 ../mysql/test_table.sql；本示例表需手工执行；组件表脚本见仓库根 sql/，同样需手工执行）
-- 测试表：验证 MyBatis-Plus 封装（MyBatisDao）与 mapper.xml 两种接入方式
-- 表结构需与 com.yomi.boot.java.qucik.starter.testtable.TestTable 字段一一对应
-- 测试接口：GET /test/ds/test-table/dao、GET /test/ds/test-table/xml
-- 时间列统一 timestamptz(3)；MySQL 版 update_time 的 ON UPDATE CURRENT_TIMESTAMP 在 PG 无对应语法
-- （应用侧 BaseEntity.update() 会显式赋值 update_time，无需数据库触发器）
CREATE TABLE IF NOT EXISTS test_table
(
    id          bigint       NOT NULL,
    name        varchar(64)  NOT NULL,
    age         integer      NOT NULL DEFAULT 0,
    remark      varchar(255),
    create_time timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by   bigint,
    update_time timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id)
);
COMMENT ON TABLE test_table IS '测试表（MP封装与mapper.xml验证）';
COMMENT ON COLUMN test_table.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN test_table.name IS '名称';
COMMENT ON COLUMN test_table.age IS '年龄';
COMMENT ON COLUMN test_table.remark IS '备注';
COMMENT ON COLUMN test_table.create_time IS '创建时间';
COMMENT ON COLUMN test_table.create_by IS '创建人ID';
COMMENT ON COLUMN test_table.update_time IS '更新时间';
COMMENT ON COLUMN test_table.update_by IS '更新人ID';
COMMENT ON COLUMN test_table.delete_time IS '删除时间';
COMMENT ON COLUMN test_table.delete_by IS '删除人ID';
