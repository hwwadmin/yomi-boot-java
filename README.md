# YomiBoot-Java

## 项目简介

**YomiBoot-Java** 是一套基于 Spring Boot 的**组件型 Boot 脚手架**：以多模块形式沉淀可复用的运行时能力与集成方式，供业务工程按需依赖组合，而不是开箱即用的完整业务系统。

- **提供什么**：统一工程分层（`base` / `core` / `component`）、Web 与横切能力（校验、AOP、全局异常、链路追踪等），以及 Token 会话、RBAC 权限、数据源、Redis、幂等、事件、对象存储等**可选组件**。
- **不提供什么**：具体业务域模型与表结构（例如用户、订单等）；业务应用自行建表、实现登录与用户体系后，再通过 `ITokenService` 签发会话、用 RBAC 绑定 `user_id` 与角色即可。
- **如何使用**：新业务工程依赖 `yomi-boot-java-core` 获得基础能力，再按场景引入 `yomi-boot-java-component` 子模块；仓库中的 `yomi-boot-java-qucik-starter` 仅作**组合与集成参考**（含各组件 flow 演示），可复制依赖与配置思路，不必当作业务模板整体 fork。各组件依赖、配置与验证方式见 **[组件使用说明](docs/组件使用说明.md)**。

### 定位

| 维度 | 说明 |
|------|------|
| 形态 | 组件库 + 核心运行时，Maven 多模块发布与依赖 |
| 目标 | 统一团队内公共能力与集成范式，降低重复造轮子 |
| 边界 | 脚手架负责「怎么接」；业务数据、流程与领域规则由业务项目承担 |

### 提示词工程（AI 协作）

[`_prompts/`](_prompts/) 里是一套给 AI 用的「写需求 → 出方案 → 审方案 → 写代码」的说明和 Java 规范，**用在业务项目里**；本仓库只管组件，不写具体业务功能。

业务项目里挂上这套 prompts（常见做法：submodule 本仓，或设置环境变量 `YOMI_PROMPTS_ROOT` 指向本仓库根），在 AI 对话里按顺序用 `prd_spec` → `create_spec` → `audit_spec` → `do_spec` 即可；文档和代码落在**业务项目根目录**，不要落在本 boot 仓。

细则见 [AI 开发工作流说明](docs/AI开发工作流说明.md)。

### 模块说明

| 模块 | 说明 |
|------|------|
| `yomi-boot-java-base` | 与 Spring 无关的基础库：工具类、通用模型、Jackson 序列化、异常与仓储接口等 |
| `yomi-boot-java-core` | 框架核心：启动引导（`AppBoot`）、统一配置、CORS、XSS/耗时切面、BCrypt、雪花 ID、全局异常处理等 |
| `yomi-boot-java-component` | 可选组件聚合：`ds`（动态数据源 + MyBatis-Plus）、`redis`、`kafka`、`token`、`rbac`、`roundrobin`、`idempotency`、`event`、`oss`、`captcha`、`desensitize` |
| `yomi-boot-java-qucik-starter` | 快速体验 / 集成参考的 Spring Boot 应用 |

## 技术栈

### 基础环境

| 技术 | 版本 |
|------|------|
| Java | 25 |
| Maven | 多模块构建 |
| Lombok | 由 Spring Boot BOM 管理 |

### 框架与云原生

| 技术 | 版本 |
|------|------|
| Spring Boot | 4.1.1 |
| Spring Cloud | 2025.1.3 |

### 数据与中间件（组件模块中使用）

| 技术 | 版本 | 用途 |
|------|------|------|
| MyBatis-Plus | 3.5.17 | ORM 与增强能力 |
| dynamic-datasource | 4.5.0 | 多数据源 |
| Redisson | 4.7.0 | Redis 客户端与分布式能力 |
| MySQL | 驱动由 Spring Boot 管理（mysql-connector-j 9.7.0） | 关系型数据库（示例配置） |
| Kafka | Spring Kafka 4.1.1（kafka-clients 4.2.1，BOM 管理） | 消息（`component-kafka`） |
| AWS S3 / RustFS / 阿里云 OSS | AWS SDK S3 2.31.77（S3 与 RustFS 共用）、阿里云 OSS SDK 0.5.1（见根 `pom.xml`） | 对象存储（`component-oss`） |

### 服务端版本（推荐与兼容区间）

以下为组件可对接的**服务端**版本，不属于本仓库依赖；客户端版本见上表。

| 服务 | 推荐版本 | Docker 镜像 tag | 兼容区间 | 关键约束 |
|------|----------|-----------------|----------|----------|
| MySQL | 8.4 LTS（8.4.11） | `mysql:8.4.11` | 8.0 – 9.7 | 8.0 扩展支持已于 2026-04-30 到期，仅存量可用；8.4 起 `mysql_native_password` 默认禁用、9.0 移除，账号须用 `caching_sha2_password`（驱动已支持） |
| Redis | 7.4（7.4.11） | `redis:7.4.11`（精简镜像可用 `redis:7.4.11-alpine`） | 6.2 – 8.x（当前稳定线 8.10） | 下限 6.2：`GETDEL` 等命令在 6.2 才提供（captcha 等组件依赖）；7.4 安全支持至 2029-12；许可为 RSALv2/SSPLv1（BSD-3 仅限 7.2 及更早，8.0 起可选 AGPLv3） |
| Kafka | Apache Kafka 4.2.2（对应 Confluent Platform 8.2.x） | `apache/kafka:4.2.2`；Confluent 发行版 `confluentinc/cp-kafka:8.2.4` | 2.1 – 4.3.x，建议 ≥ 3.0 | 4.x 仅支持 KRaft（ZooKeeper 已移除）、broker 需 Java 17+；官方兼容口径：≤ 2.0 不可用、2.1–2.8 部分兼容、3.x 完全兼容 |
| RustFS | 1.0.1（2026-10-03；1.0.0 GA 于 2026-09-16） | `rustfs/rustfs:1.0.1` | S3 兼容，建议 ≥ 1.0.0（本仓实测 1.0.1） | Apache-2.0、Rust 实现，定位 S3 兼容并与 MinIO 迁移共存；**本地 Docker 测试 S3 兼容性的推荐选型**（1.0.2 仍为 preview，选稳定版 1.0.1）；容器以非 root（UID 10001）运行、bind mount 目录需可写；S3 API 9000 / 控制台 9001，官方镜像默认账号 rustfsadmin |

以上镜像 tag 均为已发布的具体补丁版本，部署时不要使用 `latest` 等滚动 tag。AWS S3 与阿里云 OSS 为云托管服务，没有自选版本；RustFS 用 `yomi.oss.provider: rustfs` 直接接入（本地 Docker 测试推荐），其他 S3 兼容存储（如 Ceph RGW、SeaweedFS）可按 `yomi.oss.provider: s3` + `use-path-style: true` 接入。

### 可观测与安全

| 技术 | 说明 |
|------|------|
| Spring Boot Actuator | 健康与指标 |
| Micrometer Tracing + Zipkin | 分布式链路（Brave） |
| Bouncy Castle | 加解密与 BCrypt 密码编码 |
| Jsoup | XSS 校验等 HTML 处理 |

## 文档

| 文档 | 说明 |
|------|------|
| [组件使用说明](docs/组件使用说明.md) | 各 component 依赖、配置与验证 |
| [AI 开发工作流说明](docs/AI开发工作流说明.md) | 提示词指令分步操作、审阅与可选指令 |
| [清洁架构与 DDD-light 方法论](docs/清洁架构与DDD-light方法论.md) | 与 spec 分层、事务约定对齐 |

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
