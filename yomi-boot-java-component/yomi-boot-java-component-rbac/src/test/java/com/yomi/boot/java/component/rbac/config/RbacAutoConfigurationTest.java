package com.yomi.boot.java.component.rbac.config;

import com.yomi.boot.java.component.rbac.IRbacAdminService;
import com.yomi.boot.java.component.rbac.repository.IRbacAuthRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacPermGroupRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacPermRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacRelationRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacRoleRepository;
import com.yomi.boot.java.component.rbac.repository.IRbacSnapshotRepository;
import com.yomi.boot.java.component.rbac.repository.RbacAuthRepository;
import com.yomi.boot.java.component.rbac.repository.RbacPermGroupRepository;
import com.yomi.boot.java.component.rbac.repository.RbacPermRepository;
import com.yomi.boot.java.component.rbac.repository.RbacRelationRepository;
import com.yomi.boot.java.component.rbac.repository.RbacRoleRepository;
import com.yomi.boot.java.component.rbac.repository.RbacSnapshotRepository;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacAuthMapper;
import com.yomi.boot.java.component.rbac.repository.mapper.RbacPermMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.StaticApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rbac 装配契约：六个仓储 Bean 与 Mapper 都由装配类注册（Mapper 由装配类的 {@code @MapperScan} 扫，
 * 宿主无需扫描 {@code com.yomi}，也不必为组件 Mapper 配置 @MapperScan）；
 * Mapper 缺失时给出带建表脚本与自动扫描说明的启动失败
 */
class RbacAutoConfigurationTest {

    private static ApplicationContextRunner runner() {
        // 关闭投影：本用例只关心仓储/Mapper 接线，不引入 Redis
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RbacAutoConfiguration.class))
                .withPropertyValues("spring.application.name=rbac-test",
                        "yomi.rbac.enabled=true", "yomi.rbac.projection-enabled=false");
    }

    @Test
    void repositoriesAndMappersWiredWithoutHostMapperScan() {
        runner().withBean(SqlSessionFactory.class, RbacAutoConfigurationTest::sqlSessionFactoryStub)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "装配失败: " + context.getStartupFailure());
                    assertInstanceOf(RbacPermRepository.class, context.getBean(IRbacPermRepository.class));
                    assertInstanceOf(RbacPermGroupRepository.class, context.getBean(IRbacPermGroupRepository.class));
                    assertInstanceOf(RbacRoleRepository.class, context.getBean(IRbacRoleRepository.class));
                    assertInstanceOf(RbacAuthRepository.class, context.getBean(IRbacAuthRepository.class));
                    assertInstanceOf(RbacRelationRepository.class, context.getBean(IRbacRelationRepository.class));
                    assertInstanceOf(RbacSnapshotRepository.class, context.getBean(IRbacSnapshotRepository.class));
                    assertNotNull(context.getBean(IRbacAdminService.class));
                    // Mapper 来自装配类的 @MapperScan（宿主未提供任何 Mapper Bean）
                    assertNotNull(context.getBean(RbacAuthMapper.class));
                    assertNotNull(context.getBean(RbacPermMapper.class));
                });
    }

    @Test
    void mapperGuardFailsWithGuidanceWhenMapperAbsent() {
        ObjectProvider<RbacPermMapper> none = new StaticApplicationContext().getBeanProvider(RbacPermMapper.class);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new RbacAutoConfiguration().rbacPermRepository(none));

        assertTrue(error.getMessage().contains("sys_rbac.sql"), error.getMessage());
        assertTrue(error.getMessage().contains("自动配置扫描"), error.getMessage());
    }

    /** 让装配类扫出的 Mapper 能实例化：真实 Configuration + 空 DataSource（不触库，仅是 Mapper 代理的宿主） */
    private static SqlSessionFactory sqlSessionFactoryStub() {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(
                RbacAutoConfigurationTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> null);
        Environment environment = new Environment("rbac-test", new ManagedTransactionFactory(), dataSource);
        return new SqlSessionFactoryBuilder().build(new Configuration(environment));
    }

}
