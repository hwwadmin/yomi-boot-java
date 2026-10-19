# JavaWeb与API规范

---

## 0. 文档信息

- **文档名称**: JavaWeb与API规范
- **描述**: 使用 `yomi-boot-java-core` / `base` 时的 HTTP API 契约：统一响应、异常、校验与鉴权组件对接。**不规定**业务 URL 风格与是否 RESTful，由 spec 自述。
- **适用**: spec Controller/API 章节、显化审核 API 维度、代码巡检 Web 层。

---

## 1. 文档定位

- **关联**：[基础库能力索引](./基础库能力索引.md)（`component-token`、`component-rbac`）、[Java事务与一致性规范](./Java事务与一致性规范.md)（写接口幂等）。

---

## 2. 统一响应模型

### 2.1 类型

| 类型 | 类 | 用途 |
|------|-----|------|
| 单对象 | `com.yomi.boot.java.base.model.Response<T>` | 成功/失败信封 |
| 分页 | `PageResponse<T>` / 团队列表 DTO | 列表 + total |

字段顺序（JSON）：`code`、`errorCode`、`debugMessage`、`timestamp`、`data`（见 `Response` 源码）。

### 2.2 成功与失败

- **成功**：`Response.success(data)` 或 `Response.success()`；`code` 为成功态（与 `StatusCode` 一致）。
- **业务失败**：抛出 `BizException`（含 `errorCode`）；由 `GlobalExceptionAdvice` 捕获，**HTTP 状态码仍为 200**，body 为 `Response.fail(...)`（与现网约定一致）。
- **参数校验**：`@Valid` 触发 `MethodArgumentNotValidException` 等，同样映射为 `Response.fail`，**禁止** Controller 内 catch 后吞掉或改用手写 Map。

### 2.3 API 契约命名

- HTTP 入参类：**`{Action}Request`**（如 `GetConfigDetailRequest`）；出参数据类：**`{Action}Response`** 或列表行 `*Response`。
- 信封：单接口 `Response<T>`；分页 `Response<PageResponse<T>>`，`T` 为 `*Response`，**不要**与业务 **Model**、表 **Entity** 混名。
- 框架分页参数字段可复用 `PageRequest`（`page`、`pageSize`）或业务 `PageXxxRequest` 内嵌同名字段。

### 2.4 spec 应写什么

- 本服务对外 **稳定 `errorCode` 字符串**（如 `ORDER_NOT_FOUND`）；权限类码由业务定义。
- **禁止**把 `debugMessage` 或堆栈作为前端契约；内部错误统一 `INTERNAL_ERROR` 类兜底说明即可。
- 分页：`total`、列表字段在 spec 与 `PageResponse` 对齐，勿混用多套信封。

演示：`yomi-boot-java-qucik-starter` 各 `*FlowController` 仅验证组件与 `Response` 信封，**不**代表业务分层。

---

## 3. Controller 约定

| 应做 | 禁止 |
|------|------|
| `@RestController` + `@RequestMapping` / `@PostMapping` 等 | 在 Controller 声明 `@Transactional` |
| `@Valid @RequestBody` 接收 JSON | 手写 `Map<String,Object>` 拼业务响应 |
| 调用 **`*UseCase#execute`** 后 `return Response.success(...)` | 在 Controller 内直接操作 Mapper 或 **DomainService/Repository** |
| 业务失败 `throw new BizException(...)` | 捕获 `Exception` 后返回 null 或 500 无 body |

**路由**：`server.servlet.context-path`、网关前缀与 `@RequestMapping` 组合成对外 URL；spec 列 **完整 URL** 与 **Controller 相对路径** 两套口径，避免联调歧义。

**方法语义**：团队可统一 POST+JSON Body 查询；若使用 GET+Query，须在 spec 显式约定。

---

## 4. 校验

- 入参：Jakarta Validation（`@NotNull`、`@Size` 等）+ 必要时 **UseCase** 或 **`DomainServiceImpl`** 内业务校验（`Assert` / `BizException`）；领域不变量优先在 DomainService。
- 分组校验、嵌套对象按 Spring 惯例；错误信息进入 `Response.fail` 的 debug 字段，前端以 `errorCode` 做 i18n（若项目有前端规程）。

---

## 5. 鉴权与会话（可选组件）

| 组件 | spec 须说明 |
|------|-------------|
| `component-token` | 哪些路径需登录、`yomi.token` 配置、skip 路径 |
| `component-rbac` | 接口权限码、与菜单/角色关系 |

未引入组件时，在 spec **非目标** 或 **安全** 章节说明自建鉴权方式。

---

## 6. 链路与其他 Header

- 追踪：`TraceMdcUtils` / MDC `traceId`（若启用）；spec 可要求传递 `traceId`。
- 幂等：写接口幂等键与 [Java事务与一致性规范](./Java事务与一致性规范.md) §4 一致（如 Header `Idempotency-Key`，取出后传给 `IdempotencyUtils.execute/run(...)`）；spec 须写明传递方式。

---

## 7. 与异构系统对接

若下游非本 `Response` 形态，在 spec **附录** 单独写映射表（字段、成功判定）；母版不强制异构信封，标 `⚠️ 待确认` 直至团队确认。

---

## 8. 禁止项

- 绕过 `GlobalExceptionAdvice` 在 Controller 统一改错误格式（除非全项目明确第二套 Advice）。
- 同一 URL 用参数分流「正向/补偿」等不同语义（补偿须独立路径或独立方法）。
- 将技术异常信息直接作为 `errorCode` 暴露给调用方。

---

## 9. 检查清单

- [ ] 是否使用 `Response` / `BizException` 而非自定义信封
- [ ] 参数校验失败是否有稳定错误语义
- [ ] 写接口是否关联幂等与事务章节
- [ ] 完整 URL、鉴权、分页字段是否在 spec 列出

---
