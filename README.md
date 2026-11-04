# yomi-boot-java

## 项目简介

**yomi-boot-java** 是基于 Spring Boot4 + JDK25 的组件脚手架：常用能力做成模块，业务工程按需引入，不用重复造轮子。它只管「怎么接」，不写业务。

- **有什么**：Token 会话、RBAC 权限、数据源、Redis、Kafka、幂等、事件、对象存储等，用哪个引哪个。
- **要注意**：不含用户、订单这类业务代码，表要自己建（脚本在 `sql/mysql|pgsql/`）；数据库只支持 MySQL 和 PostgreSQL。
- **怎么用**：先引 `yomi-boot-java-core`，再按需加 `component` 下的子模块；`qucik-starter` 是参考示例。

### 提示词工程（AI 协作）

[`_prompts/`](_prompts/) 是一套给 AI 用的协作说明（写需求 → 出方案 → 审方案 → 写代码）和 Java 规范，用在**业务项目**里；本仓库只管组件，不写业务功能。

用法：业务项目把本仓 `_prompts/` **整体拷入工程根目录**，在 AI 对话里依次用 `prd_spec` → `create_spec` → `audit_spec` → `do_spec`，产出的文档和代码落在工程根目录。细则见 [AI 开发工作流说明](docs/AI开发工作流说明.md)。


## 中间件推荐版本

| 服务 | 推荐版本 | Docker 镜像 tag | 兼容区间 |
|------|----------|-----------------|----------|
| MySQL | 8.4 LTS（8.4.11） | `mysql:8.4.11` | 8.0 – 9.7 |
| PostgreSQL | 17（本仓实测 16.15） | `postgres:16-alpine` | 12 – 18 |
| Redis | 7.4（7.4.11） | `redis:7.4.11`（精简镜像用 `redis:7.4.11-alpine`） | 6.2 – 8.x（当前稳定线 8.10） |
| Kafka | Apache Kafka 4.2.2（对应 Confluent Platform 8.2.x） | `apache/kafka:4.2.2`；Confluent 版 `confluentinc/cp-kafka:8.2.4` | 2.1 – 4.3.x，建议 ≥ 3.0 |
| RustFS | 1.0.1 | `rustfs/rustfs:1.0.1` | S3 兼容，建议 ≥ 1.0.0（本仓实测 1.0.1） |


## 文档

| 文档 | 说明 |
|------|------|
| [组件使用说明](docs/组件使用说明.md) | 各 component 依赖、配置与验证 |
| [AI 开发工作流说明](docs/AI开发工作流说明.md) | 提示词指令分步操作、审阅与可选指令 |
| [清洁架构与 DDD-light 方法论](docs/清洁架构与DDD-light方法论.md) | 与 spec 分层、事务约定对齐 |

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
