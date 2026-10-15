package com.yomi.boot.java.component.ds.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Instant 的 JDBC 类型处理器：写参数走 {@code setTimestamp(Timestamp.from(instant))}，读取用 {@code getTimestamp} 还原。
 * <p>
 * 用于覆盖 MyBatis 内置的 {@code InstantTypeHandler}：后者写库用 {@code setObject(Instant)}，
 * PostgreSQL 驱动无法推断类型会直接抛"无法推测实例 java.time.Instant 的 SQL 类型"（MySQL 驱动支持），
 * 不换就会导致 PG 下所有含 Instant 字段的写库失败。换成本处理器后 MySQL / PG 行为一致：
 * Instant 与库中 {@code datetime(3)} / {@code timestamptz(3)} 按瞬时往返，精度到毫秒。
 * <p>
 * 由 ds 自动装配注册为 Bean，MyBatis-Plus 收集后按 javaType=Instant 注册（覆盖内置映射），宿主无需配置。
 *
 * @author zwei
 */
@MappedTypes(Instant.class)
@MappedJdbcTypes(value = {JdbcType.TIMESTAMP, JdbcType.TIMESTAMP_WITH_TIMEZONE}, includeNullJdbcType = true)
public class InstantJdbcTypeHandler extends BaseTypeHandler<Instant> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.from(parameter));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toInstant(rs.getTimestamp(columnName));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toInstant(rs.getTimestamp(columnIndex));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toInstant(cs.getTimestamp(columnIndex));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

}
