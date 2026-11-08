package com.yomi.boot.java.component.httplog.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * t_sys_http_log 的 Mapper：由 {@link com.yomi.boot.java.component.httplog.repository.HttpLogRepository} 独占使用
 * <p>
 * 数据库操作经仓储访问（查询服务/落库 store/保留期清理都走仓储；装配侧只把它注入仓储）。
 * CRUD 与批量插入（BATCH 会话）由 MyBatis-Plus 提供；保留期限额删除与最旧时间查询是项目约定的 XML 例外
 * （限额删除需按方言拆两条；最旧时间需按 java.time.Instant 映射结果），见 {@code mapper/HttpLogMapper.xml}。
 *
 * @author zwei
 */
public interface HttpLogMapper extends BaseMapper<HttpLogEntity> {

    /** 保留期清理：限额物理删除该服务 {@code create_time} 早于阈值的行，返回删除行数 */
    int deleteExpiredLimited(@Param("serviceName") String serviceName,
                            @Param("createTimeBefore") Instant createTimeBefore,
                            @Param("limit") int limit);

    /** 该服务最旧一行的 {@code create_time}（表空返回 null） */
    Instant selectOldestCreateTime(@Param("serviceName") String serviceName);

}
