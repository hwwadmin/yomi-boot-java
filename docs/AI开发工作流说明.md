# AI 开发工作流说明

面向 **yomi-boot-java** 生态的 AI 协作流程：业务功能在**依赖本脚手架的业务 Maven 工程**中落地；本仓库内仅做 **组件 / 核心能力维护**（禁止写入产品领域逻辑）。指令定义见仓库内 [`_prompts/commands/`](../_prompts/commands/)；执行时 AI 会加载同名 Skill（[`_prompts/skills/`](../_prompts/skills/)）与 Java 知识库（[`_prompts/knowledge/`](../_prompts/knowledge/)）。

目录与指令速查见仓库根 [README.md](../README.md)「提示词工程」一节。组件能力与集成方式见 [组件使用说明](组件使用说明.md)；基础设施选型前须对照 [基础库能力索引](../_prompts/knowledge/基础库能力索引.md)。

---

## 适用场景

| 场景 | 工作区 | 代码落点 | 文档落点 |
|------|--------|----------|----------|
| **业务开发**（默认） | 业务 Maven 工程（依赖 `yomi-boot-java-core` 及按需 `component-*`） | 业务工程 `src/main/java` 等 | 业务工程根目录下的 `_doc/`、`_feat-doc/` 等 |
| **boot 库维护** | 本仓库 `yomi-boot-java` | 仅 `yomi-boot-java-base` / `core` / `component-*` / `qucik-starter` 演示 | 本仓库根目录下同名文档目录（与上表相同文件夹名） |

**boot 库边界**：不在 boot 仓实现产品域表结构、业务流程与用户体系；`qucik-starter` 的 `test*` 包仅作组件集成演示。业务 spec 须在「本项目结构约定」中写清包名与分层，并优先复用已有 component。

---

## `_prompts` 如何被找到

AI 执行任一指令前须定位 `_prompts`（与各 command 第 1 步一致），按顺序尝试：

1. 当前打开工程的根目录 `_prompts/`
2. 环境变量 `YOMI_PROMPTS_ROOT`（指向含 `_prompts` 的 `yomi-boot-java` 根或其父路径）
3. Git submodule / 同级克隆的 `yomi-boot-java/_prompts/`
4. （可选）`yomi-boot-java-prompts` 依赖包内的 `_prompts/`

均未找到则终止并列出已尝试路径。定位成功后：**Skill / knowledge 相对该 `_prompts` 根**；**PRD、spec、tasks 等产物写入当前业务工程根**（在 boot 库维护场景下即本仓库根）。

业务工程通常将本仓 `_prompts` 作为 submodule 或设置 `YOMI_PROMPTS_ROOT`，在 IDE 中打开业务工程工作区即可使用同一套指令。

---

## 工作流概览

一条需求从提出到 Java 代码落地的完整路径：

| ① prd_spec | → | ② create_spec | → | ③ 审阅修订 | → | ④ audit_spec | → | ⑤ 审核闭合 | → | ⑥ do_spec |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| 充分讨论后 AI 生成 PRD | | AI 生成技术 spec | | 人工指引 | | AI 生成审核 | | 人工+AI 修订 | | AI 落地代码 |

核心原则：**审核不通过，不写一行代码**。主闭环在 `do_spec` 结束；其后可用可选指令生成质量文档或做代码巡检。

本套母版**不含前端规程**（无 React / Go / DTM 等）；`create_spec` / `do_spec` 产出为后端技术方案与实现。

---

## 文档目录约定

以下目录均在**当前工程根**（业务仓或 boot 维护时的本仓根）创建，日期 `YYYYMMDD` 为生成日：

| 目录 | 用途 |
|------|------|
| `_doc/` | 产品需求文档（PRD） |
| `_feat-doc/{YYYYMMDD}_{需求名称}/` | `spec.md`（需求显化）、`audit.md`（审核） |
| `_task-doc/{需求名称}/` | `tasks.md`（实现任务与进度） |
| `_quality-doc/{YYYYMMDD}_{需求名称}/` | `test.md`、`accept.md`（可选） |
| `_ipc-doc/` | 代码巡检报告 `*-ipc.md`（可选） |

PRD 与业务口径以 `_doc/` 为准；**不要**把 `_prompts/knowledge/` 当作产品文档。

---

## 指令速查

在对话中输入指令名（可加参数，见各步说明）。AI 应读取 `_prompts/commands/{指令}.md` 并全文执行对应 Skill。

| 指令 | 必选 | 作用 | 产物 |
|------|------|------|------|
| `prd_spec` | 主路径 | 根据当前会话生成产品业务需求文档 | `_doc/{YYYYMMDD}_{需求名称}-prd.md` |
| `create_spec` | 主路径 | 生成 Java 技术方案（需求显化） | `_feat-doc/{YYYYMMDD}_{需求名称}/spec.md` |
| `audit_spec` | 主路径 | 审核技术方案 | `_feat-doc/{YYYYMMDD}_{需求名称}/audit.md` |
| `do_spec` | 主路径 | 落地代码（须 audit 闭合） | `_task-doc/{需求名称}/tasks.md` + 代码变更 |
| `quality_doc` | **可选** | 生成测试用例与产品验收文档 | `_quality-doc/.../test.md` + `accept.md` |
| `check_code` | **可选** | Java 代码质量巡检 | `_ipc-doc/{YYYYMMDD}_{需求名称}-ipc.md` |
| `deep_think` | **可选** | 对当前问题做第一性原理 + 对抗性审查 | 对话结论（不落盘） |

---

## 第一步：prd_spec — 生成产品需求文档（PRD）

需求讨论充分后，在对话中输入：

```
prd_spec
```

AI 基于当前会话中已确认的需求、决策与补充说明生成 PRD，并参考**当前工程** `_doc/` 中已有 PRD 保持业务口径一致。产物保存到：

> `_doc/{YYYYMMDD}_{需求名称}-prd.md`

PRD 从产品与业务视角描述需求背景、目标用户、业务目标、使用场景、需求范围、业务流程、功能需求、业务规则和验收标准等，明确标记假设、冲突及待确认事项，**不包含**技术选型、系统架构、接口或代码实现等技术细节。

若目标文档已存在，AI 会结合当前讨论增量修订，并保留仍然有效的业务内容。

---

## 第二步：create_spec — 生成开发方案（spec.md）

在对话中输入：

```
create_spec：{功能名称}
需求来源：_doc/{文档名}.md
```

（若无 PRD，也可在会话中直接给出已确认的需求描述，但仍建议后续补 PRD 以对齐验收口径。）

AI 加载 `_prompts/skills/需求显化.md`，并必读 `_prompts/knowledge/` 下 Java 相关规范（数据访问、事务与一致性、Web/API、表设计、Redis、基础库能力索引等），生成技术方案到：

> `_feat-doc/{YYYYMMDD}_{需求名称}/spec.md`

产物侧重 **Java 后端**：DDL / 表设计、领域模型、服务方法与编排、事务与一致性、Repository 边界、Controller 与 API、错误码等；须在 spec **「本项目结构约定」** 中写明业务工程的包名与分层。方案须优先采用 `yomi-boot-java-component-*` 已有能力，避免平行造轮子。

**不包含**：前端页面与 UI 实现、部署运维方案、压测方案。

---

## 第三步：阅读并指引 AI 修订方案

AI 生成的 spec 是初稿，你需要审阅并指引修订：

- 标记 `⚠️ 待确认` 的项需要你决策
- 与 [基础库能力索引](../_prompts/knowledge/基础库能力索引.md) 或组件边界不符时直接指出
- 业务规则理解有误的地方直接纠正

示例指令：

> 「将用户名格式确认为：小写字母、数字、下划线，回写到 spec」
>
> 「登录失败统一返回 USER_LOGIN_FAILED，不区分用户不存在和密码错误」
>
> 「会话使用 component-token，权限走 component-rbac，在 spec 中写明依赖与配置项」

---

## 第四步：audit_spec — 生成审核文档

在对话中输入：

```
audit_spec _feat-doc/{YYYYMMDD}_{需求名称}/spec.md
```

AI 执行 `_prompts/skills/显化审核.md`，按项目规范全面审核，生成同目录下的 `audit.md`。

问题分三级：

| 级别 | 含义 |
|------|------|
| 🔴 严重 | 逻辑矛盾或规范冲突（含写路径事务两步法等门禁），**必须修正**，否则阻塞实现 |
| 🟡 警告 | 不一致或遗漏，**建议修正** |
| 🔵 建议 | 可优化项，可在实现阶段处理 |

---

## 第五步：审核闭合 — 修订至通过

根据 `audit.md` 中的发现，逐一指引 AI **回写 spec**（审核 Skill 不直接改 spec 正文），再次运行 `audit_spec` 复审。

**闭合标准**（必须全部满足）：

- 🔴 严重问题 = 0
- 审核结论声明「**是否存在阻塞问题：否**」+「**修正后是否可进入实现阶段：是**」

---

## 第六步：do_spec — 落地代码

审核通过后，在对话中输入：

```
do_spec _feat-doc/{YYYYMMDD}_{需求名称}/spec.md
```

AI 执行 `_prompts/skills/spec落地执行.md`，将：

1. 确认 `audit.md` 无未闭合严重项，阅读 spec（及可选 PRD）
2. 拆解为分阶段原子任务，生成 `_task-doc/{需求名称}/tasks.md`（名称通常与 spec 目录中的需求名一致）
3. 按 **TDD** 逐个功能点推进：先写测试 → 再写实现 → 必要时小步重构；验证以 `mvn test` / 模块测试为主，HTTP 集成可参考 `yomi-boot-java-qucik-starter` 中 `*ApiTest` 模式
4. 每完成一个功能点回写 `tasks.md`

实现约束摘要：

- 启动惯例：`AppBoot.run` + `@SpringBootApplication`（core 与 component 均为自动装配，无需 `scanBasePackages = "com.yomi"`；`@MapperScan` 只扫业务自己的 Mapper 包，如 `com.your.app.**.repository.mapper`——组件的 Mapper 由组件自动配置类各自扫描，宿主无需关心依赖 jar）
- 不得绕过 spec 中的事务与 API 契约；不得在业务仓重复实现已有 component 能力
- 在 **boot 库维护** 场景下，仅改动组件与演示代码，禁止实现产品业务域

---

## 附：可选指令

以下指令**不纳入主闭环门禁**，彼此互不前置，也不由 `do_spec` 自动触发。

### quality_doc — 测试用例与产品验收文档

需要给测试 / 产品提供文档时：

```
quality_doc _feat-doc/{YYYYMMDD}_{需求名称}/spec.md
```

可选补充 PRD：

```
需求来源：_doc/{YYYYMMDD}_{需求名称}-prd.md
```

AI 基于已审 `spec.md`（及 PRD）派生两份文档到：

> `_quality-doc/{YYYYMMDD}_{需求名称}/test.md`（测试用例）  
> `_quality-doc/{YYYYMMDD}_{需求名称}/accept.md`（产品验收）

建议在审核闭合、口径稳定后再生成；不依赖 `check_code`。

### check_code — 代码质量巡检

代码落地后（或 boot 组件改动后）：

```
check_code
```

或指定范围：

```
check_code：{模块名或包路径}
```

示例：`check_code：yomi-boot-java-component-token` 或 `check_code：com.example.order`

AI 执行 `_prompts/skills/代码巡检.md`，报告落盘至：

> `_ipc-doc/{YYYYMMDD}_{需求名称}-ipc.md`

未关联某次需求显化时，可使用 `_ipc-doc/{YYYYMMDD}_全项目-ipc.md`。巡检范围在 **boot 仓** 默认为 `base` / `core` / `component-*` / `qucik-starter` 演示；在 **业务仓** 为用户指定包或默认 `src/main/java` 业务代码。不把测试覆盖率作为硬性门禁，重点关注崩溃、资源与并发风险、契约破坏等（见 command 内审查标准）。

### deep_think — 深度思考

对复杂方案或争议点，可在任意阶段使用：

```
deep_think
```

仅输出分析结论与待确认项，不替代 `audit_spec` 门禁。

---

## AI 协作建议

1. **打开正确的工作区**：业务功能在业务工程仓库中跑完整闭环；改 component 时打开 `yomi-boot-java` 并遵守 boot 边界。
2. **一条需求一个 spec 目录**：便于 `audit`、`tasks`、`ipc` 同名关联。
3. **先索引后编码**：涉及 Redis、幂等、消息、OSS 等时，在对话中要求 AI 先读 [基础库能力索引](../_prompts/knowledge/基础库能力索引.md) 再改 spec 或代码。
4. **指令即契约**：用户输入 `prd_spec` / `create_spec` 等时，AI 应以 `_prompts/commands` 与对应 Skill 为准，而不是即兴缩短流程。
