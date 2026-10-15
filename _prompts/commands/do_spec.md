---
description:基于spec文档开始落地代码
---

1. **定位 `_prompts`（强制）**：按顺序尝试：① 当前项目根 `_prompts/`；② 环境变量 `YOMI_PROMPTS_ROOT`（`yomi-boot-java` 仓库根，或该根下含 `_prompts` 的父路径）；③ Git submodule / 同级克隆的 `yomi-boot-java/_prompts/`；④（可选）`yomi-boot-java-prompts` 依赖中的 `_prompts/`。均未找到则**终止**并列出已尝试路径。定位成功后，`_prompts/skills`、`knowledge` 均相对该根；业务产物（`_doc/`、`_feat-doc/`、`_task-doc/`、`_quality-doc/`、`_ipc-doc/`）写入**当前业务工程根**（boot 库维护模式除外）。

再**全文执行** `_prompts/skills/spec落地执行.md`（《spec落地执行》）。

2. 阅读指定的需求显化文档和产品需求文档（当前业务工程内路径，如 `_feat-doc/`、`_doc/`）

3. 思考过程需要基于第一性原理和对抗性审查进行

4. 根据该文档思考功能点，规划功能点落地步骤，拆解为原子化的任务清单（Tasks），明确每个任务的输入、输出及依赖关系，并按阶段分组

5. 将生成的工作流输出为 tasks.md 文件，文件放置路径为**当前业务工程**根目录的 `_task-doc/{spec文档名 / 需求名称}/tasks.md`

6. 根据步骤规划，逐步实现功能点，每次实现一个功能都需要回写 tasks.md 文件，记录落地过程

7. 开发落地过程按功能点规划 TDD 推进：一个功能点对应一组测试方法，先补该组测试方法，再补实现，必要时小步整理代码

8. 必须严格遵守文档契约
