# YomiBoot-Java

## 项目简介

**YomiBoot-Java** 是一套基于 Spring Boot 的**组件型 Boot 脚手架**：把可复用的运行时能力和接入方式沉淀成多个模块，业务工程按需依赖组合，不是开箱即用的完整业务系统。定位是团队内共用一套公共能力与接入范式——脚手架只管「怎么接」，业务数据、流程和规则归业务项目。

- **提供什么**：统一的工程分层（`base` / `core` / `component`）和一批常用能力——校验、全局异常、链路追踪、Token 会话、RBAC 权限、数据源、Redis、幂等、事件、对象存储等，按需引入。
- **不提供什么**：不写具体业务（用户、订单这类表和领域模型都没有）。业务自己建表、自己做登录，然后调 `ITokenService` 签发会话、用 RBAC 把用户和角色绑起来就行；组件自己要用的表不用手工建，启动时自动建。
- **数据库支持范围**：**只兼容 MySQL 和 PostgreSQL，没做达梦（DM）和 Oracle**——换成这两类库，启动时会直接失败报错，不会带着跑不通的 SQL 硬上。两库的差异（建表脚本、个别语句）组件内部已处理。
- **如何使用**：业务工程先依赖 `yomi-boot-java-core` 拿基础能力，再按需引入 `yomi-boot-java-component` 的子模块。仓库里的 `yomi-boot-java-qucik-starter` 只是**组合与集成参考**（含各组件演示），复制依赖与配置思路即可，不必整体 fork；各组件的依赖、配置与验证方式见 [组件使用说明](docs/组件使用说明.md)。

### 提示词工程（AI 协作）

[`_prompts/`](_prompts/) 是一套给 AI 用的协作说明（写需求 → 出方案 → 审方案 → 写代码）和 Java 规范，用在**业务项目**里；本仓库只管组件，不写业务功能。

用法：业务项目把本仓 `_prompts/` **整体拷入工程根目录**，在 AI 对话里依次用 `prd_spec` → `create_spec` → `audit_spec` → `do_spec`，产出的文档和代码落在工程根目录。细则见 [AI 开发工作流说明](docs/AI开发工作流说明.md)。

### 模块说明

| 模块 | 说明 |
|------|------|
| `yomi-boot-java-base` | 与 Spring 无关的基础库：工具类、通用模型、Jackson 序列化、异常等 |
| `yomi-boot-java-core` | 框架核心：启动入口（`AppBoot`）、统一配置、CORS、全局异常、XSS 与耗时切面、BCrypt、雪花 ID 等 |
| `yomi-boot-java-component` | 可选组件：`ds`（主数据源 + MyBatis-Plus；附加库由业务自建，见《组件使用说明》多数据源使用规范）、`redis`、`kafka`、`token`、`rbac`、`idempotency`、`event`、`oss`、`captcha`、`desensitize` |
| `yomi-boot-java-qucik-starter` | 快速体验 / 集成参考的 Spring Boot 应用（含各组件演示） |

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

### 数据与中间件（组件模块里用到）

| 技术 | 版本 | 用途 |
|------|------|------|
| MyBatis-Plus | 3.5.17 | ORM 与增强能力 |
| Redisson | 4.7.0 | Redis 客户端与分布式能力 |
| MySQL | 驱动由 Spring Boot 管理（mysql-connector-j 9.7.0） | 关系型数据库（示例默认；与 PostgreSQL 并列支持） |
| PostgreSQL | 驱动由 Spring Boot 管理（postgresql 42.7.x） | 关系型数据库（与 MySQL 并列支持） |
| Kafka | Spring Kafka 4.1.1（kafka-clients 4.2.1，BOM 管理） | 消息（`component-kafka`） |
| AWS S3 / RustFS / 阿里云 OSS | AWS SDK S3 2.31.77（S3 与 RustFS 共用）、阿里云 OSS SDK 0.5.1（见根 `pom.xml`） | 对象存储（`component-oss`） |

### 服务端版本（推荐与兼容区间）

下面是能对接的**服务端**版本（不是本仓依赖），客户端版本见上表。

| 服务 | 推荐版本 | Docker 镜像 tag | 兼容区间 | 上手要注意 |
|------|----------|-----------------|----------|------------|
| MySQL | 8.4 LTS（8.4.11） | `mysql:8.4.11` | 8.0 – 9.7 | 8.0 已过扩展支持（2026-04-30 到期），只建议存量用；8.4 起账号要用 `caching_sha2_password`（9.0 移除旧插件，驱动已支持） |
| PostgreSQL | 17（本仓实测 16.15） | `postgres:16-alpine` | 12 – 18 | DDL/SQL 用到 9.5+ 的 `CREATE INDEX IF NOT EXISTS` 和 9.1+ 的「GROUP BY 主键」函数依赖豁免；时间列统一 `timestamptz(3)` |
| Redis | 7.4（7.4.11） | `redis:7.4.11`（精简镜像用 `redis:7.4.11-alpine`） | 6.2 – 8.x（当前稳定线 8.10） | 下限 6.2：captcha 等组件用到 `GETDEL`；7.4 安全支持到 2029-12；许可为 RSALv2/SSPLv1 |
| Kafka | Apache Kafka 4.2.2（对应 Confluent Platform 8.2.x） | `apache/kafka:4.2.2`；Confluent 版 `confluentinc/cp-kafka:8.2.4` | 2.1 – 4.3.x，建议 ≥ 3.0 | 4.x 只有 KRaft（ZooKeeper 已移除）、broker 要 Java 17+；2.1–2.8 部分兼容，3.x 起完全兼容 |
| RustFS | 1.0.1 | `rustfs/rustfs:1.0.1` | S3 兼容，建议 ≥ 1.0.0（本仓实测 1.0.1） | 本地 Docker 测 S3 兼容性的推荐选型；容器以非 root（UID 10001）运行，挂载目录要可写；S3 API 9000 / 控制台 9001 |

上面的镜像 tag 都是已发布的具体版本，别用 `latest`。**数据库只支持上表两类——MySQL 和 PostgreSQL，没做达梦（DM）、Oracle 等其他库**。AWS S3 与阿里云 OSS 是云服务、没有自选版本；RustFS 用 `yomi.oss.provider: rustfs` 接入，其他 S3 兼容存储（Ceph RGW、SeaweedFS 等）用 `yomi.oss.provider: s3` + `use-path-style: true`。

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
