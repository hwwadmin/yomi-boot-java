# yomi-boot-java

## 项目简介

基于 Spring Boot4 + JDK25 的组件脚手架，不含业务规则。

当前底座已足够支撑绝大多数企业业务。

可以按需自行搭建出轻量单体、模块化大单体、分布式微服务等形态。


## 中间件推荐版本

| 服务 | 推荐版本 | Docker 镜像 tag | 兼容区间 |
|------|----------|-----------------|----------|
| MySQL | 8.4 LTS（8.4.11） | `mysql:8.4.11` | 8.0 – 9.7 |
| PostgreSQL | 17（本仓实测 16.15） | `postgres:16-alpine` | 12 – 18 |
| Redis | 7.4（7.4.11） | `redis:7.4.11`（精简镜像用 `redis:7.4.11-alpine`） | 6.2 – 8.x（当前稳定线 8.10） |
| Kafka | Apache Kafka 4.2.2（对应 Confluent Platform 8.2.x） | `apache/kafka:4.2.2`；Confluent 版 `confluentinc/cp-kafka:8.2.4` | 2.1 – 4.3.x，建议 ≥ 3.0 |
| RustFS | 1.0.1 | `rustfs/rustfs:1.0.1` | S3 兼容，建议 ≥ 1.0.0（本仓实测 1.0.1） |


## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
