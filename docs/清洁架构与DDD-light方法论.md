# 清洁架构 + DDD-light 方法论

> 工程分层与业务边界约定，用于模块化单体（或多入口同仓）的设计与评审。  
> 以方法论为主，辅以必要的骨架指称；不绑定具体业务或某一套中间件实现。  
> **适用范围**：依赖 **yomi-boot-java** 的**业务工程**（产品仓）。`yomi-boot-java` 仓仅提供 `base` / `core` / `component-*` 与集成演示，**禁止**写入产品领域逻辑（见 [基础库能力索引](../_prompts/knowledge/基础库能力索引.md)）。

---

## 目录

1. [定位与原则](#1-定位与原则)
2. [仓库骨架](#2-仓库骨架)
3. [分层与依赖](#3-分层与依赖)
4. [领域模块](#4-领域模块)
5. [编排入口](#5-编排入口)
6. [规则归属](#6-规则归属)
7. [跨域与装配](#7-跨域与装配)
8. [写路径与错误](#8-写路径与错误)
9. [与 MVC / 经典 DDD](#9-与-mvc--经典-ddd)
10. [演进：领域模块独立发布](#10-演进领域模块独立发布)
11. [检查清单与反模式](#11-检查清单与反模式)

---

## 1. 定位与原则

### 1.1 一句话

> **清洁架构定依赖方向；DDD-light 定业务边界；模块化单体定同仓协作与演进节奏。**

| 痛点 | 回答 |
| ---- | ---- |
| 规则散落在接口 / 存储层 | 收敛到领域服务 |
| 多入口复制业务逻辑 | 领域复用；差异只在编排 |
| 跨域乱引用 | 只依赖对方公开面；类型随域走 |
| 过重 DDD 拖慢交付 | 保留上下文与契约，不强制完整战术仪式 |

### 1.2 核心原则

1. **依赖向内**：编排 → 领域公开面 → 领域内部 → 基础设施。  
2. **领域无传输细节**：无路由、无协议 DTO、无请求上下文解析。  
3. **UseCase 不做业务规则**：UseCase 负责身份上下文、一致性边界、跨域组合、DTO 映射。  
4. **契约优于实现**：对外暴露服务接口与领域错误；实现进私有区。  
5. **类型随域**：跨边界类型放在本域 **`{domain}-api` 模块**（勿平铺域根、勿上收仓级「公共 model」包）。  
6. **横切靠外**：幂等 / 事务等由 UseCase 包裹；领域保持可组合、可单测。  
7. **战术按需升级**：先有边界与可测规则，再按痛点加重 DDD 战术。

### 1.3 术语与角色

| 构件（Maven module → jar） | 说明 |
| -------------------------- | ---- |
| `{app}-starter` | 组合根：`@SpringBootApplication`、依赖 `{app}-biz` 与所需的 `{domain}-service`；负责把领域实现装配进 Spring 容器 |
| `{app}-biz` | 业务编排：Controller、UseCase；**仅**依赖各 `{domain}-api`（及 `app-support`、boot），**不得**依赖任何 `{domain}-service` |
| `{domain}/pom.xml` | 单域聚合 POM，下辖本上下文两个子模块 |
| `{domain}-api` | 契约接口、领域错误码、跨边界类型（`api.model` / `input` / `output` / `enums`）；尽量无 Web、无 ORM |
| `{domain}-service` | 领域实现、仓储、表 `*Entity`、本域 `*DomainConfiguration`；除 `{app}-starter` 外，其他模块不得依赖 |
| `app-support` | 编排层共用工具（非业务类型）；`{app}-biz` 可依赖 |
| Controller | `@RestController`：协议绑定与委托（位于 `{app}-biz`） |
| UseCase | `{app}-biz` / `usecase` 包中的 `*UseCase`：单用例编排与写路径一致性边界；**一个类对应一个可命名用例** |

模块名中的 `{domain}`、`{app}` 由业务 spec 替换（例如 `order-api`、`mall-admin-biz`）。

**不设 Application 层**：清洁架构里的「应用层」在本方法中**不**作为单独分层或包名保留；编排职责全部由 **UseCase** 承担。UseCase 是颗粒度更细的工作单元（相对常见的 Application Service / 大而全应用服务），便于单测、复用与多入口组合。

HTTP 信封、`BizException`、`Response<T>` 等横切契约由 **yomi-boot-java-base / core** 提供；业务领域错误码在领域契约中定义，编排层映射为 `BizException`（见 [JavaWeb与API规范](../_prompts/knowledge/JavaWeb与API规范.md)）。

---

## 2. 仓库骨架

```text
{product}-repo/                    # 业务工程（Maven Reactor）
├── pom.xml                        # 父 POM、dependencyManagement、模块列表
├── {app}-starter/                 # 可执行 jar：启动 + 引入 biz 与各 domain-service
├── {app}-biz/                     # 编排 jar：Controller、UseCase（只依赖 *-api）
├── order/                         # 示例：单域聚合 POM（{domain}/pom.xml）
│   ├── pom.xml
│   ├── order-api/                 # {domain}-api.jar
│   └── order-service/             # {domain}-service.jar
├── payment/
│   ├── pom.xml
│   ├── payment-api/
│   └── payment-service/
├── app-support/                   # 仅编排层工具（非业务类型）
├── script/                        # 构建 / 库表脚本等（按需）
└── ui/                            # 客户端经网络调编排，不直连领域
```

### 2.1 Maven 依赖（编译期边界）

```text
{app}-starter
  ├── {app}-biz
  └── order-service, payment-service, …    # 仅 starter 依赖实现 jar

{app}-biz
  ├── order-api, payment-api, …
  ├── app-support（可选）
  └── yomi-boot-java-core 等（按需 component）

order-service
  └── order-api（+ component-ds 等基础设施）

order-api
  └── 尽量仅 yomi-boot-java-base 或零 Spring 依赖
```

**约束写在 pom 里**：`{app}-biz` 若声明 `{domain}-service` 依赖，应在评审中直接驳回；违规引用实现类也无法通过编译。

多入口时：可为每个入口各建一对 `{entry}-biz` + `{entry}-starter`，**共享**同一批 `{domain}-api` / `{domain}-service`；差异在 biz 的路由、权限与用例组合，不在领域规则复制。

依赖 **yomi-boot-java**（常见落点：`*-service` 引 `component-ds`，`*-biz` 引 `core` 与幂等/鉴权等；版本由 BOM 管理）：

```xml
<dependency>
    <groupId>com.yomi</groupId>
    <artifactId>yomi-boot-java-core</artifactId>
</dependency>
<!-- 按需：component-ds、component-token、component-idempotency、component-event … -->
```

| 层 | 职责 |
| -- | ---- |
| `{app}-starter` | 进程入口、扫描范围、聚合各域 `*-service` 的自动配置 |
| `{app}-biz` | 接口适配、`usecase` 包内各 `*UseCase` |
| `{domain}-api` / `{domain}-service` | 契约与类型 / 规则实现与持久化 |
| `app-support` | 会话投影、错误映射等编排边界工具 |

---

## 3. 分层与依赖

### 3.1 请求链路

```text
外部请求 → Controller（适配）→ UseCase（编排）→ Domain Service（规则）→ Repository → 存储
```

### 3.2 与 Clean Architecture 的对应

| Clean 层 | 落点 |
| -------- | ---- |
| Entities | 本域 `{domain}-api` → `api.model`（`*Model`，跨边界业务语义） |
| Domain | `{domain}-service` 内实现 + `{domain}-api` 中的 `*DomainService` |
| Use Cases | `{app}-biz` / `usecase` 下的 `*UseCase`（**不用 Application 统称**） |
| Adapters | `{app}-biz` 的 controller；`{domain}-service` 的 repository / `*DomainConfiguration` |
| Frameworks | Spring Web、MyBatis-Plus、Redis 等（可替换；优先 yomi-boot-java component） |

刻意把「领域规则」与「用例编排」拆开，避免领域依赖会话、幂等等用例层横切。`{app}-biz` 是编排**模块**；模块内以 **UseCase 类**为边界，而不是再套一层 Application Service。

### 3.3 依赖硬约束

```text
{app}-biz ──► {domain}-api（契约 + 公开类型）
                    │
                    ▼
              {domain}-service（实现；仅 starter 与域内依赖）

{app}-starter ──► {app}-biz + 所需的 {domain}-service
```

| From | 可以 | 禁止 |
| ---- | ---- | ---- |
| `{app}-biz` | 各 `{domain}-api`、`app-support`、boot 组件 | 任意 `{domain}-service`、外域 `entity` / Mapper |
| `{domain}-service` | 本域 `{domain}-api`、基础设施；其他域 **`{other}-api`**（按需） | `{app}-biz`、`app-support`、外域 `{other}-service` |
| `{app}-starter` | `{app}-biz`、本应用需要的 `{domain}-service` | 在 starter 中写业务规则（应留在 biz / domain） |
| `app-support` | 编排边界公共能力 | `{domain}-service` 与仓储 |

**口诀**：领域依赖了编排或外域 `{domain}-service` → 打回。

`*DomainConfiguration` 放在 **`{domain}-service`**，经 `AutoConfiguration` 或 starter 的依赖链注册 Bean；**不要**放进 `{domain}-api`，避免 api jar 传递拉入实现。

---

## 4. 领域模块

### 4.1 标准骨架

**默认：`{domain}-api` + `{domain}-service` 双模块（Maven 编译期隔离实现）**

```text
order/                               # {domain}/pom.xml 聚合
├── order-api/
│   └── src/main/java/.../order/api/
│       ├── model/                   # 跨边界业务模型（*Model）
│       ├── input/                   # 领域入参（*Input，非 HTTP Request）
│       ├── output/                  # 领域出参（*Output）
│       ├── enums/
│       ├── OrderDomainService.java  # 契约接口
│       └── OrderErrorCode.java      # 稳定业务 errorCode
└── order-service/
    └── src/main/java/.../order/
        ├── service/                 # DefaultOrderDomainService 等
        ├── repository/              # *Repository、*Mapper
        ├── entity/                  # *Entity + @TableName（仅 service 模块）
        └── config/
            └── OrderDomainConfiguration.java   # 装配本域 Bean
```

| 模块 / 包 | 可见性 | 说明 |
| --------- | ------ | ---- |
| `{domain}-api` / `api/*` | 公开 | 跨边界类型；可独立发版 |
| `*DomainService` + 领域错误 | 公开 | 能力接口 + 业务语义 |
| `{domain}-service` | 实现构件 | `{app}-biz` 不得依赖；跨域不得依赖外域 service |
| `*DomainConfiguration` | 随 service 发布 | 由 `{app}-starter` 引入对应 service jar 后生效 |

默认实现与仓储放在 `{domain}-service`。仅当编排或 Configuration 必须直接引用类型时，才把策略类提升到 `{domain}-api`。

可选：领域事件类型定义在 `{domain}-api`，发布实现在 `{domain}-service`（可结合 `component-event`）。

### 4.2 本域公开类型

路径：`{domain}-api` → `.../api/model|input|output|enums`。  
**不要**把跨域类型平铺在域根包，也**不要**上收成仓级 `common-model` 模块。

跨域需要对方类型 → Maven 依赖对方 `{other}-api`。  
两域争改同一 `*Model` → 先怀疑边界或做防腐，而不是再抽「公共 model 模块」。

与 `app-support` 不同：前者是业务类型，后者是编排工具；领域不得依赖 `app-support`。

与 [Java数据访问规范](../_prompts/knowledge/Java数据访问规范.md) 对齐：

| 概念 | 本方法论落点 | 用途 |
| ---- | ------------ | ---- |
| 跨边界业务语义 | `*-api` / `api.model`（`*Model`） | 领域服务入参/出参、跨域契约 |
| HTTP 契约 | 编排层 `*Request` / `*Response` | 仅 Controller / UseCase 边界 |
| 表映射 | `{domain}-service` / `entity`（`*Entity`） | 仅仓储适配 |

### 4.3 双实体（领域模型 vs 持久化）

| | 领域模型（公开） | 持久化模型 |
| - | ---------------- | ---------- |
| 位置 | `{domain}-api` / `api.model`（`*Model`） | `{domain}-service` / `entity`（`*Entity`） |
| 用途 | 跨边界传递、表达业务含义 | 对接 MyBatis-Plus / 表结构 |
| 转换 | 仅在 Repository 内完成（Entity ↔ Model） | |

跨边界只返回公开 `*Model`（或 `api.output`）；`*Entity` 不泄漏出 `{domain}-service`。

---

## 5. 编排入口

```text
{app}-starter/                          # 薄：启动与依赖聚合
└── src/main/java/.../
    └── {App}Application.java           # @SpringBootApplication

{app}-biz/                              # 编排 jar
└── src/main/java/.../biz/
    ├── controller/                     # 薄适配（@RestController）
    ├── usecase/                        # *UseCase（单用例编排）
    ├── middleware/                     # 过滤器、拦截器扩展（按需）
    └── support/                        # 本入口浅层错误映射等
```

`{app}-starter` 的 `pom.xml` 声明对 `{app}-biz` 与各 `{domain}-service` 的依赖；`spring-boot-maven-plugin` 打在 starter 上产出可执行 jar。

| 角色 | 做 | 不做 |
| ---- | -- | ---- |
| Controller | 路由、`@Valid` 绑定、`Response.success`、委托 UseCase | 业务分支、多域组合 |
| UseCase | 身份上下文、浅校验、Request ↔ 领域 Input/Model、调 `*DomainService`；写路径包一致性边界 | 复制领域规则 |

**两类 Input**：协议侧 `*Request` 在 usecase/controller 边界；领域侧 `*Input` 在 `*-api` / `api.input`。UseCase 负责转换。

示例（片段，包名按 spec 替换）：

```java
@RestController
@RequiredArgsConstructor
public class CreateOrderController {
    private final CreateOrderUseCase createOrderUseCase;

    @PostMapping("/orders")
    public Response<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return Response.success(createOrderUseCase.execute(request));
    }
}

@RequiredArgsConstructor
public class CreateOrderUseCase {
    private final OrderDomainService orderDomainService;
    private final IdempotencyGuard idempotencyGuard; // 横切在编排，见 component-idempotency

    @Transactional(rollbackFor = Exception.class)
    public CreateOrderResponse execute(CreateOrderRequest request) {
        CreateOrderInput input = toDomainInput(request);
        OrderModel created = orderDomainService.create(input);
        return toResponse(created);
    }
}
```

---

## 6. 规则归属

| 问题 | 放哪 |
| ---- | ---- |
| 聚合完整性、状态机、唯一性等 | Domain |
| 换入口仍成立的规则 | Domain |
| 跨域步骤编排 | UseCase |
| 会话 / 幂等 / 事务边界 | UseCase |
| 协议空值、分页默认 | UseCase（浅层，不替代领域规则） |

**口诀**：换入口仍成立 → Domain；依赖本次会话 / 幂等 / 多域组合 → UseCase。

```text
新逻辑 → 仅单域规则？ → Domain
       → 跨域 / 会话 / 协议横切？ → UseCase
```

---

## 7. 跨域与装配

**允许**：UseCase 组合多个 `*DomainService`；装配期注入其他域契约；按需领域事件（可结合 `component-event`）。  
**禁止**：`{app}-biz` 依赖外域 `{domain}-service`；复制外域 `*Entity`；新建仓级业务 model 模块。

**归属三问**

1. 换入口是否仍同一规则？→ 是则偏领域  
2. 是否必须深入两个上下文内部？→ 是则拆契约，编排组合  
3. 配置还是运行时实例？→ 常属不同上下文，勿揉成万能 Model  

**两级装配**：域内 `OrderDomainConfiguration`（在 `order-service`）注册 `OrderDomainService` → `{app}-starter` 依赖 `order-service` 拉入自动配置。  
横切能力（`ITokenService`、`IdempotencyGuard` 等）注入 UseCase，不穿透进 `Default*DomainService` 实现。

---

## 8. 写路径与错误

```text
Controller → UseCase →（幂等 / @Transactional 等一致性边界）→ *DomainService
```

领域错误在 `*-api` 中定义稳定 `errorCode` 语义；编排捕获后 `throw new BizException(...)`，**不改写**业务含义。  
浅层协议错误放在入口 `support` 或 core 全局异常 advice 的映射扩展。

与 [Java事务与一致性规范](../_prompts/knowledge/Java事务与一致性规范.md)：**`@Transactional` 默认挂在 UseCase（编排闭包）**，不在 Controller；领域 Service 不声明 Web 相关注解，不把 Redis/MQ 调用放进 DB 事务闭包。

---

## 9. 与 MVC / 经典 DDD

### 9.1 vs MVC

#### 要解决的主矛盾不同

| | MVC（及常见「MVC + Service」三层） | 本方法（Clean + DDD-light） |
| - | -------------------------------- | --------------------------- |
| 经典目标 | **界面与数据的分离** | **业务策略与交付入口的分离** |
| 典型场景 | 页面驱动、以表单 CRUD 为主 | 多入口 API、规则复杂、领域需长期复用 |
| 成功标准 | 页面可维护、交互清晰 | 规则可测、可复用、依赖可评审 |

客户端继续用 MVC / MVVM 完全正常。服务端真正的痛点，往往不是「没有 Service」，而是：

> Controller 变薄之后，**Service 变成上帝类**：一个 Service 注入大量 Repository，跨表 / 跨模块交叉读写，规则、流程、持久化搅在一起。

本方法针对的正是这种「入口瘦了、服务胖了」的结构性膨胀。

#### 结构对照

| 维度 | 典型 MVC / 三层 | 本方法 |
| ---- | -------------- | ------ |
| 主分离轴 | Controller / Service / Model(Repo) | 编排 / 领域 / 基础设施 |
| Controller | 可薄；薄了不等于架构健康 | 极薄：绑定与委托 |
| **Service** | **易成上帝类**：流程 + 规则 + 多 Repository 交叉 | 拆成 **UseCase（编排）** 与 **Domain Service（单域规则）** |
| 仓储使用 | 任意 Service 可注入多 Repository，边界靠自觉 | 仓储留在 `{domain}-service`；跨上下文只经 `{domain}-api`，不经 Repository |
| Model | 常混数据 + 持久化 + 部分规则 | `*Entity` 私有；跨边界用 `{domain}-api` 的 `*Model` |
| 业务边界 | 常按页面 / 接口 /「某某 Service」切 | 按有界上下文切 |
| 多入口 | 易复制或继续堆进大 Service | 领域复用；入口只换编排 |
| 依赖方向 | 常无强制 | **依赖向内**为硬约束 |

#### 上帝类 Service 为何会出现

1. **按接口堆方法**：新接口就在「对应 Service」里加一段，Service 随接口数膨胀。  
2. **用 Repository 当集成点**：跨表协作不经明确边界，直接在 Service 里注入对方 Repository。  
3. **编排与规则不分**：事务、校验、状态机、查库全写在同一类。  
4. **缺少有界上下文**：没有「哪些概念不能进这个 Service」的硬约束。  

结果是：Controller 看起来很干净，系统复杂度全部沉到 Service——更难测、更难拆、更难复用。

#### 职责落点

| 职责 | MVC / 三层常落点 | 本方法落点 |
| ---- | ---------------- | ---------- |
| 参数绑定 / 响应映射 | Controller | Controller |
| 跨模块流程、身份、幂等、事务 | 大 Service | UseCase（编排） |
| 单上下文不变量 / 状态机 | 同一大 Service | `*DomainService` |
| 跨上下文协作 | Service 里交叉调多 Repository / 多 Service | 只调对方 **`{domain}-api` 契约**；组合放在 `{app}-biz` |
| 持久化细节 | Service 直持多 Repository | 领域私有仓储；不对外暴露 |

#### 优缺点

**MVC / 三层优点**

- 概念少、路径短，上手快  
- 小系统、以存取为主时交付快  
- Controller 变薄后，短期内「看起来很干净」  

**MVC / 三层缺点**

- **Service 上帝类**：方法与依赖持续膨胀  
- **Repository 交叉**：跨边界协作靠直接掏仓储，有界上下文名存实亡  
- 多入口时规则易复制，或继续汇入同一个大 Service  
- 纯业务规则难单测，常与事务、多表访问绑死  

**本方法优点**

- 用「UseCase vs 领域」拆开上帝类的两种职责  
- 跨上下文禁止 Repository 交叉，只能走契约  
- 规则可单测、可复用；依赖可评审（Maven 模块 + 包）  

**本方法缺点**

- 前期概念与样板更多（Controller → UseCase → Domain）  
- 需要统一「规则放哪 / 用例边界怎么切」，否则易出现空 UseCase 或胖 Domain Service  
- 若偷建仓级业务 model 模块，仍会重新耦合  

#### 如何选择 / 落地顺序

| 信号 | 更合适 |
| ---- | ------ |
| 单入口、简单存取、生命周期短 | MVC / 三层往往够用 |
| 多入口、规则复杂、单 Service 职责过重、Repository 跨上下文交叉 | 本方法 |
| 入口已薄，但业务类难测、难拆 | 按编排与领域拆分职责，避免再抽「公共 BaseService」 |

落地顺序（方法论）：

1. **先划定有界上下文**，明确各 `{domain}-api` 契约与非目标  
2. **跨上下文只经 `{domain}-api` 调用**；多域步骤放在 `{app}-biz` 的 UseCase  
3. **单上下文规则收敛到 `*DomainService`**；Controller 保持薄  

依赖方向由模块与包结构体现：仅调整命名而不约束「谁持有哪些 Repository」，评审时仍应视为违规。

**共存**：客户端 MVC/MVVM + 服务端本方法，是常见且健康的组合。

### 9.2 DDD-light

**采用**：有界上下文、统一语言、服务契约、域内仓储、按需事件 / 策略。  
**弱化**：显式聚合根、跨层公开仓储、默认全域事件、CQRS/ES、繁琐值对象。

| 经典 | 本方法 |
| ---- | ------ |
| 富聚合根 | 公开 `*Model` + Domain Service 守不变量（可渐进变富） |
| 仓储作跨层端口 | 仓储私有，对外只有 `*DomainService` |
| Shared Kernel | 类型在 `{domain}-api`，不上收仓级 |
| Application Service 大包承载多用例 | 拆为多个 **UseCase** + **Domain Service**（单域规则） |

---

## 10. 演进：领域模块独立发布

同仓协作是起点。领域从第一天就是 `{domain}-api` + `{domain}-service` 两个构件，终局将整个域或至少 api/service 拆到独立仓库或私服坐标，由消费方以标准依赖引入。

```text
阶段 1  同仓 Reactor：`{app}-biz` 只依赖 *-api，starter 聚合 *-service
阶段 2  `{domain}-api` / `{domain}-service` 独立版本号发版（私服 / Maven Central）
阶段 3  其他应用或编排仓 pom 只引用已发版的 api；starter 引用已发版的 service
```

依赖方向不变，只变「构件从哪解析、如何发版」。  
公开面始终是：`{domain}-api` 契约与本域 `api` 类型；`{domain}-service` 永不当编排层的编译依赖；禁止再抽仓级业务 model 模块。

| 更宜留在同仓 | 更宜独立发布 |
| ------------ | ------------ |
| 契约 / 类型剧烈抖动 | 公开面已稳定 |
| 消费方少且同团队 | 多入口 / 多仓消费 |
| 本地联调是主要矛盾 | 发布节奏互相阻塞 |

**口诀**：先有界、后版本、再拆仓；Maven 依赖是目标，本地 Reactor 只是加速器。

---

## 11. 检查清单与反模式

### 新领域

1. 写清上下文与非目标  
2. 建 `{domain}/pom.xml` 子模块：`{domain}-api`、`{domain}-service`（含 `*DomainConfiguration`）  
3. 纳入 Reactor 与 dependencyManagement；补领域单测（直接测 `Default*DomainService`，Mock Repository）  
4. `{app}-starter` 依赖 `{app}-biz` 与所需 `{domain}-service`  

### 新接口

1. 规则归领域，还是先补 `*-api` 契约？  
2. UseCase（写路径接一致性边界）+ 薄 Controller + 装配  

### 禁止

1. Controller 直访 Repository 或写 SQL 细节  
2. Domain 依赖 `HttpServletRequest` / 会话解析  
3. UseCase 复制领域校验  
4. `{app}-biz` 依赖 `{domain}-service`，或跨域依赖外域 `*-service` / `internal` 包  
5. 仓级业务 model 模块，或把编排类型塞进 `*-api`  
6. Domain 依赖 `app-support`  
7. 领域私自接管全局 `@Transactional` / 幂等（应留在 `*UseCase`）  

---

## 附录：最小心智模型

```text
编排（{app}-biz）：怎么接请求、怎么组合多域、怎么保证写一致
领域（{domain}-service）：什么合法、状态怎么变、怎么存
类型（{domain}-api）：随域模块走
启动（{app}-starter）：依赖 biz + 各 domain-service，不写业务规则
```

方法论管原则与边界；工程约定管目录与技术选型；规格管业务；代码与测试是真源。  
业务工程 spec「本项目结构约定」须写明包名与模块名，并与 [Java数据访问规范](../_prompts/knowledge/Java数据访问规范.md)、[JavaWeb与API规范](../_prompts/knowledge/JavaWeb与API规范.md) 的对象命名一致。
