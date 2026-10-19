# Java事务与一致性规范

---

## 0. 文档信息

- **文档名称**: Java事务与一致性规范
- **描述**: 本地事务、传播关系、幂等与跨边界（跨模块/跨进程）一致性；适配 `component-idempotency`、`component-event`。**本栈无 DTM/Saga/共享 DB 分布式事务。**
- **适用**: spec「事务与一致性设计」、显化审核事务门禁、代码巡检。

---

## 1. 文档定位

- **用途**：为需求显化 §3.4.2「两步法」提供权威选项与禁止项；不规定业务是否微服务，只规定 **不能假设** 的集成语义。
- **关联**：[Java数据访问规范](./Java数据访问规范.md)、[基础库能力索引](./基础库能力索引.md)、[Redis设计规范](./Redis设计规范.md)。

---

## 2. 本地事务（Spring）

### 2.1 默认

| 项 | 要求 |
| -- | ---- |
| `@Transactional` | **对外写用例默认**在 **`*UseCase#execute`**；**领域内**仅在 spec 写明时标在 **`*DomainServiceImpl` 写方法**（同一写链路二者不同时标注） |
| `IdempotencyUtils` | **唯一入口**；幂等边界同样在 `*UseCase#execute`——把写逻辑包进 `execute` / `run` 的闭包（静态调用，不用注解） |
| Controller | 不声明事务 |
| 同类 `this.xxx()` | 不承载 `@Transactional`（同类自调用不走代理）、不重复包幂等；业务写事务与幂等闭包都标在 `execute` |

### 2.2 闭包边界（Step 2）

| 闭包内（同一 `@Transactional`） | 闭包外 |
|--------------------------------|--------|
| 本库多表 INSERT/UPDATE/DELETE；经 Mapper/Repository 的 DB 操作 | Redis、HTTP、MQ、OSS、发通知 |
| **默认**事务在 `*UseCase#execute` 时：Request→Model→Response 映射同属该闭包 | 领域内单独落点时，按 spec 界定闭包上下界 |

缓存失效、token 撤销、发通知等 **不得** 与 DB 写处于同一 `@Transactional` 闭包；在 `execute` 内编排时须在提交后执行（闭包外步骤、`TransactionSynchronization#afterCommit` 等）。

外部调用失败 **不会** 随 DB 事务回滚；须在 spec 写补偿、重试或最终一致方案。

---

## 3. 传播关系（spec Step 1 选项）

与需求显化「写动作事务决策表」列对齐：

| 选项 | 含义 | 典型场景 |
|------|------|----------|
| 不适用 | 纯读或未写 DB | 查询接口 |
| REQUIRED | 单库多表/多域原子写（默认）；对应 `@Transactional(propagation = REQUIRED)` | 订单+明细同库；**同库跨域**编排 `execute` 单事务 |
| REQUIRES_NEW | 独立子事务 | 审计日志必须提交（**须强理由**） |
| 无 DB 事务 | 不写本库，或仅 Redis/OSS/消息等 | 纯缓存写、上传、编排方无领域表 |
| 编排方无 DB 事务 | 编排方 UseCase **不写本库**；下游各自 `@Transactional`（常见为被调方 `*DomainServiceImpl`） | 跨进程编排、编排方纯协调 |
| 外部在事务外 | 本地 REQUIRED，HTTP/Redis/MQ **不进** DB 闭包 | 写库后发通知、缓存失效 |
| 跨边界无 DB 传播 | 被调方本地开事务，与编排方**无**共享 DB 事务 | 跨进程 HTTP 写接口 |
| 最终一致 | 本地事务提交后，事件/outbox/对账 | `component-event` + Kafka |

spec Step 1 表须同时填写 **是否开本地 DB 事务**（开/不开）与本列 **传播关系**；二者含义不同，禁止混为一列省略。

---

## 4. 幂等（component-idempotency）

| 项 | 约定 |
|----|------|
| 组件 | `yomi-boot-java-component-idempotency` |
| 用法 | `IdempotencyUtils.execute/run(namespace, key, ...)` + 业务键（唯一入口：静态调用，无注解、不注入守卫） |
| 与 DB | 幂等键（如 Header `Idempotency-Key`，取出后作为 `IdempotencyUtils` 的 key 参数传入）与 **业务唯一索引** 分工：前者防重复提交，后者防脏数据；spec 须分别说明 |
| 与事务 | 边界由调用方给，组件**不创建也不注入**事务模板：`@Transactional` 与 `IdempotencyUtils` 闭包同点（写用例入口）时守卫直接沿用；没有事务时把 `TransactionTemplate` 作为 `execute/run` 的 `template` 入参传入（只接受 REQUIRED、非只读）；两者都没有直接抛 `IDEMPOTENCY_TRANSACTION_REQUIRED`。记录库**跟着事务走**（事务落哪个库就写哪个库），未注册的库抛 `IDEMPOTENCY_DATASOURCE_UNREGISTERED`，线程上另绑别的库事务抛 `IDEMPOTENCY_FOREIGN_TRANSACTION` |

**禁止**：用幂等组件替代业务唯一约束、假设幂等可回滚已提交的跨边界副作用；闭包内不要再开 `REQUIRES_NEW`（业务先独立提交、记录随外层回滚，重试会重复执行）；没有事务边界就调用幂等（组件不自建事务）。附加库要用幂等必须先在装配期经 `IdempotencyDataSources` 注册（注册即获得自动建表与自动清理）。

---

## 5. 跨边界一致性（无分布式 DB 事务）

业务可能是：**同进程多模块**、**多 JVM HTTP**、**消息驱动**。母版只约束下列必须项。

### 5.1 栈能力

- 同步：**多模块单体**优先进程内编排方 `*UseCase` 调用各模块导出的 `*DomainService`；**多服务**时由 spec 声明远程集成方式。
- 异步：`component-event`（发件箱 + Kafka），消费侧配合 `EventInboxGuard` 去重（见 [组件使用说明](../../docs/组件使用说明.md)）。
- **不提供**：跨服务单库事务、DTM、Saga 引擎。

### 5.2 spec 必须写清（有跨边界写时）

1. **集成方式**：同进程调用 / HTTP / 事件（勿留「待定」进入实现）。
2. **顺序**：先调 A 再调 B；失败时是否回滚本地、是否调补偿接口。
3. **失败语义**：下游超时、业务错误码、是否重试、最大重试次数。
4. **补偿或最终一致**（二选一或组合）：
   - **补偿**：正向接口 + 补偿接口（独立路由/方法）；补偿数据由编排方在请求体中带**快照**，补偿方不得再 HTTP 查外服务拼数据。
   - **最终一致**：本地事务 + 发事件；对账任务与幂等键。
5. **副作用**：token 撤销、缓存失效等 **不得** 假定为 DB 事务的一部分，除非 spec 明确接受不一致窗口。

### 5.3 禁止

- 设计「编排方一个 @Transactional 覆盖多库」。
- 在 DB 事务闭包内发起阻塞 HTTP 并依赖其回滚。
- 未写补偿/对账却要求跨边界强一致。

---

## 6. 与需求显化两步法对照

```text
Step 1  每个写动作：是否写数据？是否开本地 DB 事务（开/不开）？传播关系（§3，与上列分列）？
Step 2  凡「开本地 DB 事务」= 开：闭包、幂等键、@Transactional 落点（**默认** `*UseCase#execute`；领域内按需 DomainServiceImpl；含被调方「跨边界无 DB 传播」）
        传播为「编排方无 DB 事务」：只写编排步骤与补偿，不写本库闭包
        另有跨模块写时：步骤顺序、正向/补偿路由、事件或对账（§5）
```

Step 1 未完成不得写 Step 2 闭包细节（显化审核严重项）。

---

## 7. 禁止项汇总

- 事务方法内长时间 HTTP / 大批量 MQ。
- 假设远程调用失败会自动回滚其他服务已提交数据（无全局事务）。
- 跨边界写只勾「需要事务」三字而无传播与失败语义。

---

## 8. 检查清单（审核 / 巡检）

- [ ] 所有写动作是否出现在 Step 1 表
- [ ] 跨边界调用是否标注「跨边界无 DB 传播」
- [ ] 幂等键与唯一索引是否分工说明
- [ ] Redis/MQ 是否在闭包外或有明确最终一致方案
- [ ] 跨边界写是否有补偿或事件+对账，而非隐含 Saga

---
