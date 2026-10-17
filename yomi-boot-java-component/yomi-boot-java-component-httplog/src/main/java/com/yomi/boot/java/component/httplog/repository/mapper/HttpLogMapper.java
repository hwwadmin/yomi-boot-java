package com.yomi.boot.java.component.httplog.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yomi.boot.java.component.httplog.entity.HttpLogEntity;

/**
 * sys_http_log 的 Mapper：由 {@link com.yomi.boot.java.component.httplog.repository.HttpLogRepository} 独占使用
 * <p>
 * 数据库操作经仓储访问（查询服务/落库 store/保留期清理都走仓储；装配侧只把它注入仓储）。
 * CRUD 由 MyBatis-Plus 提供、零自定义 SQL（运行时跨库）；批量插入（BATCH 会话）与两阶段删除在仓储内封装。
 *
 * @author zwei
 */
public interface HttpLogMapper extends BaseMapper<HttpLogEntity> {

}
