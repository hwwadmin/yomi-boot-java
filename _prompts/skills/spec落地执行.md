# spec落地执行

---

## 0. 基础信息

- **Skill 名称**: spec落地执行
- **描述**: 在 `audit.md` 门禁通过后，基于 `spec.md` 拆解 `tasks.md` 并以 TDD 在业务 Java 工程中落地实现
- **作者**: zwei
- **创建日期**: 2026-10-10

---

## 关联知识库

- **执行前必读**：`knowledge/Java事务与一致性规范.md`、`knowledge/JavaWeb与API规范.md`、`knowledge/基础库能力索引.md`
- **执行前建议读**：`knowledge/Java数据访问规范.md`（UseCase/Helper/Repository 链）；分层以 spec §2.3 为准

## 1. 目标

- **做什么**：阅读 `_feat-doc/.../spec.md`（及可选 `_doc/` PRD、`audit.md`），生成 `_task-doc/.../tasks.md`，按任务 TDD 实现并回写 tasks。
- **为什么**：将已审核的技术 spec 转为可追踪的实现与验证。
- **不做什么**：不修改 spec/audit 正文；不触发 quality_doc / check_code；不写前端（本母版不含前端规程）。

---

## 2. 规则

1. 涉及基础设施必须先读 `_prompts/knowledge/基础库能力索引.md`（含优先使用流程）。
2. 分层遵循 spec §2.3「本项目结构约定」；事务与幂等遵循《Java事务与一致性规范》。
3. **yomi-boot-java 根目录**：仅允许组件/演示改动，禁止实现产品业务域。

---

## 3. 工作流程

- **Step 1**：确认 `audit.md` 无未闭合严重项（尤其事务）；读 spec 全章。
- **Step 2**：拆解 tasks.md（原子任务、依赖、输入输出）。
- **Step 3**：按任务 TDD：`mvn test` / 模块测试；HTTP 集成测试可参考 `qucik-starter` 的 `*ApiTest`（**仅测组件**，非分层参考）。
- **Step 4**：每完成一任务回写 tasks.md 状态与证据。
- **Step 5**：最小范围验证，不扩大改动面。

---

## 4. 约束

- 不得绕过 spec 中的事务与 API 契约。
- 不得在业务仓重复实现已有 component 能力。
- 启动类惯例：`AppBoot.run` + `@SpringBootApplication`（core 与 component 均为自动装配，无需 `scanBasePackages = "com.yomi"`；`@MapperScan` 只扫业务自己的 Mapper 包，如 `com.your.app.**.repository.mapper`——组件的 Mapper 由组件自动配置类各自扫描）。

---

## 5. DoD

- [ ] tasks.md 已生成且与 spec 范围一致
- [ ] 实现符合 spec 分层与组件选型（含第十章 `*UseCase#execute`、§2.3 Maven 模块）
- [ ] 写路径 `@Transactional`（默认 `execute`）与幂等入口（`IdempotencyUtils` 闭包）与 spec 落点一致；无同类自调用承载事务
- [ ] 相关测试已执行并通过或说明未执行原因
