# Java数据访问规范

---

## 0. 文档信息

- **文档名称**: Java数据访问规范
- **描述**: 使用 `yomi-boot-java-component-ds`（MyBatis-Plus；主库由组件装配、附加库由业务自建）时的数据访问约定；**不规定**业务工程包名与分层命名，仅约束与 boot 集成时的持久化形态与安全底线。
- **适用**: 业务 Maven 工程；《需求显化》《显化审核》《代码巡检》中 SQL/Repository 相关章节。

---

## 1. 文档定位

- **用途**：约束表实体、Mapper/Repository、查询安全、分页与 spec 中「查询–索引对照」的落地口径。
- **边界**：不覆盖分库分表运维、ORM 选型辩论；业务分层目录由 spec「本项目结构约定」自述。
- **关联**：[数据库表设计规范](./数据库表设计规范.md)、[Java事务与一致性规范](./Java事务与一致性规范.md)、[基础库能力索引](./基础库能力索引.md)（`component-ds`）。

---

## 2. 技术栈与启用

| 项 | 约定 |
|----|------|
| 组件 | `yomi-boot-java-component-ds` |
| ORM | MyBatis-Plus + 可选 XML Mapper |
| 封装 | `com.yomi.boot.java.component.ds.MyBatisDao<M, E>` |
| 扫描 | 启动类 `@MapperScan` 只覆盖业务自己的 Mapper 包（示例 `com.your.app.**.repository.mapper`，**业务可改**）；组件的 Mapper 由各组件自动配置类自己扫，业务不必扫依赖 jar |
| 实体基类 | `BaseEntity`（审计字段、软删字段与表设计规范一致） |
| 主键 | 雪花 ID（`yomi.id` / `IdUtils`，与表设计规范一致） |

组件演示：`yomi-boot-java-qucik-starter` 仅用于 **集成测试**（如 `TestTableFlowApiTest`），**不得**作为业务分层样板；`TestTableFlowController` 直调 Repository 仅验证 MyBatis。

---

## 3. 对象分层（命名强制，包路径见 spec §2.3）

| 概念 | 命名 | 职责 | 禁止 |
|------|------|------|------|
| **Entity** | `*Entity` | 与表字段一一对应；`extends BaseEntity`（`Instant` ↔ `datetime(3)`） | 作为 HTTP Request/Response；混入业务编排 |
| **Model** | `*Model` | 业务语义、组合字段、无 `@TableName` | 混入 `@TableField`；直接当 API 契约 |
| **Request / Response** | `*Request` / `*Response` | Controller 与 UseCase 入参/出参（Jackson） | 在 Repository 层使用 |
| **Mapper** | `*Mapper` | SQL 访问声明 | 业务规则、事务边界 |
| **DomainRepository** | `*DomainRepository` / `*DomainRepositoryImpl` | 领域仓储端口 + 实现；**Entity ↔ Model**、调用 Mapper | UseCase/Controller 直调；须经 DomainService |

数据流：`Request` → **UseCase**（`execute`）→ **DomainService** → **DomainRepositoryImpl** → `Response`。共享片段用 **`*Helper`**。仓储端口在 **domain-api**，实现在 **infrastructure**；usecase 不依赖 infrastructure。

---

## 4. MyBatisDao 与 Repository

### 4.1 推荐形态

```java
@Repository
public class OrderDomainRepositoryImpl extends MyBatisDao<OrderMapper, OrderEntity> {
    // 实现 OrderDomainRepository 端口；Entity ↔ OrderModel
    // 复杂条件：优先 LambdaQueryWrapper；无法满足再用 XML
}
```

- **对外**：本库读写经 **UseCase → DomainService → DomainRepositoryImpl**；**禁止** Controller/UseCase 直注 Mapper（`qucik-starter` 演示除外）。
- **继承 `MyBatisDao`**：与组件演示一致的链式 API（产品代码放在 `*DomainRepositoryImpl`）。

### 4.2 XML Mapper

- 命名：`*Mapper.xml` 与接口同模块；复杂报表、多表 join、数据库特有函数使用 XML。
- 动态 SQL 必须使用 `#{}` 参数绑定；**禁止** `${}` 拼接用户输入。
- spec 中列出 XML 方法语义、入参、结果类型及依赖索引。

### 4.3 多数据源

- 主库走 `spring.datasource.*`（组件装配出 `masterXxx` 四件套，全 `@Primary`）；**附加库的数据源、`SqlSessionFactory`、事务管理器由业务自建**，不要使用已移除的 `spring.datasource.dynamic.*`。
- 自建附加库时 `@MapperScan` 必须写齐 `sqlSessionFactoryRef` 与 `sqlSessionTemplateRef`（只写一个会被主库模板/工厂劫持，启动即失败），附加库事务显式指定 `transactionManager`。
- 单请求跨库写：须在 [Java事务与一致性规范](./Java事务与一致性规范.md) 中单独决策（默认避免，无 XA）；组件默认对跨库访问报错（`yomi.ds.transaction-guard=error`）。

---

## 5. 查询、分页与索引

- **列表/搜索**：spec 必须写清过滤字段、排序、分页方式（offset / 游标），并与 [数据库表设计规范](./数据库表设计规范.md) 中索引对照表一致。
- **分页**：入参可与 `PageRequest`（Web 层）或内部 `Page` 对象对齐；出参避免一次查询无 `limit` 扫全表。
- **深分页**：大表 offset 方案须在 spec 标注风险或改用游标/搜索方案。
- **只读**：只读方法可不加事务或使用 `@Transactional(readOnly = true)`（团队可选）；写路径见事务规范。

---

## 6. 缓存与 DB 一致性

- Redis 缓存键、TTL、失效策略见 [Redis设计规范](./Redis设计规范.md)。
- **默认**：缓存刷新、双删、消息通知 **不** 与 DB 写同一 `@Transactional` 闭包（避免长事务与回滚不一致）；若业务强一致，须在 spec Step 1/2 写明接受的风险与补偿。
- 禁止在无 spec 说明时默认「先更新 DB 再删缓存」以外的自创顺序。

---

## 7. 安全与禁止项

| 禁止 | 说明 |
|------|------|
| SQL 注入 | 禁止拼接用户输入；动态列名/表名须白名单 |
| 在事务中扫大表 | 批处理须分页/限流，spec 写并发契约 |
| N+1 | 列表接口须在 spec 说明 join/批量查询策略 |
| 在 boot `component-*` 内写产品表 | 平台表仅在组件 `sql/` 目录；产品 DDL 在业务仓 |

---

## 8. spec / 审核检查清单（摘要）

- [ ] Entity / Model 与 API Request/Response 是否分离
- [ ] 写路径是否 UseCase → DomainService → DomainRepositoryImpl，而非 Controller/UseCase 直调 Mapper
- [ ] `@Transactional` 落点是否与 spec 一致（写用例默认 `*UseCase#execute`；领域内按需 DomainServiceImpl）
- [ ] 列表查询是否具备索引对照与分页说明
- [ ] 是否存在 XML/原生 SQL；若有，参数是否绑定
- [ ] 缓存与 DB 写顺序是否在事务章节说明

---
