-- MySQL 建表脚本（PostgreSQL 版见同目录 ../pgsql/test_table.sql；本示例表需手工执行；组件表脚本见仓库根 sql/，同样需手工执行）
-- 测试表：验证业务自建仓储（BaseMapper）与 mapper.xml 两种接入方式
-- 表结构需与 com.yomi.boot.java.qucik.starter.testtable.TestTable 字段一一对应
-- 测试接口：GET /test/ds/test-table/dao、GET /test/ds/test-table/xml
CREATE TABLE IF NOT EXISTS `test_table`
(
    `id`          bigint       NOT NULL COMMENT '主键ID（雪花ID）',
    `name`        varchar(64)  NOT NULL COMMENT '名称',
    `age`         int          NOT NULL DEFAULT 0 COMMENT '年龄',
    `remark`      varchar(255)          DEFAULT NULL COMMENT '备注',
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `create_by`   bigint                DEFAULT NULL COMMENT '创建人ID',
    `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `update_by`   bigint                DEFAULT NULL COMMENT '更新人ID',
    `delete_time` datetime(3)           DEFAULT NULL COMMENT '删除时间',
    `delete_by`   bigint                DEFAULT NULL COMMENT '删除人ID',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='测试表（MP封装与mapper.xml验证）';
