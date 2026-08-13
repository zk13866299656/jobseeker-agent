# 切片 5：工具集扩展（3 个记忆驱动工具）— 设计文档

日期：2026-08-13
仓库：`jobseeker-agent`
技术栈（SRS 强制锁定）：SpringBoot3 + Maven + Java17

---

## 1. 背景与目标

切片 1 交付了 2 个演示工具（`study_plan` / `interview_question`），都是「纯模板」——写死规则、不碰数据。本切片把工具从「会聊天」升级到「真能干活」：新增 3 个工具，**都读取切片 3 的长期记忆做个性化**，把「工具 + 记忆」串起来，这是比纯模板强得多的卖点。

目标：验证「工具读取用户画像 → 产出个性化结果」的闭环，同时保持工具架构的干净与可测。

## 2. 范围

### 2.1 本切片做什么

新增 3 个记忆驱动工具：

| 工具名 | 工具标识 | 读记忆 | 产出 |
|---|---|---|---|
| 岗位匹配 | `job_match` | 目标岗位、技能 | 匹配度排序 + 缺失技能 + 补强建议 |
| 简历优化 | `resume_improve` | 技能、求职进度、目标岗位 | STAR 简历要点 / 经历改写 |
| 错题复盘 | `mistake_review` | 技能、目标岗位 | 结构化复盘卡（框架） |

同时把 `Tool` 接口的 `execute` 签名从 `execute(params)` 改为 `execute(userId, params)`，让工具能拿到用户身份去读记忆。

### 2.2 本切片不做什么（留待后续）

- **文件导出**（学习计划/简历导出成下载文件）——本质是后端「生成文件」功能，不是「模型调用的工具」，单独切片做。
- 登录注册 / 多用户隔离——继续用「浏览器匿名编号」做身份（见复盘清单第 7 条）。
- 工具内部调 LLM——本切片工具保持纯 Java 规则。
- 岗位库接真实招聘数据源——本切片用内置静态岗位库。

## 3. 关键技术决策

### 3.1 工具如何拿到「用户是谁」

- 现在 `Tool.execute(Map<String,Object> params)` 没有用户信息。改为：
  ```java
  String execute(String userId, Map<String, Object> params);
  ```
- 前端已稳定生成匿名 `userId`（localStorage 随机 UUID），`AgentLoop.run` 已持有它，调用点从 `tool.execute(params)` 改为 `tool.execute(userId, params)`。
- 需要读记忆的新工具注入 `MemoryService`，在 `execute` 内 `memoryService.load(userId)` 拿到 `List<UserMemory>`。
- 老工具（`StudyPlanTool` / `InterviewQuestionTool`）签名同步加 `userId` 但忽略不用，保持接口统一。

### 3.2 工具保持「纯 Java 规则」，不内部调 LLM

- 三个新工具内部不调用 `LlmClient`，只做确定性计算 + 模板拼接，与现有 2 个工具一致。
- 「正确思路」这类需要知识的内容，由 `AgentLoop` 的最终答案（LLM）补全：工具出「结构化骨架 + 个人数据」，LLM 出「润色与展开」。
- 好处：工具确定、好测（喂固定记忆 → 断言输出）；职责清晰（工具=规则，LLM=知识）。

### 3.3 工具只读记忆，不写记忆

- 记忆的「写」仍由切片 3 的 `MemoryExtractor` 统一管（每次回答后 LLM 抽取）。
- 工具只读不写，避免出现两套写路径、以及工具写记忆与抽取器写记忆冲突。

## 4. 三个工具详细设计

### 4.1 `JobMatchTool`（job_match）

- **描述**：根据用户目标岗位和技能，匹配软工应届生岗位方向并指出技能差距。
- **参数**（模型可传，均可选）：
  - `role`：指定方向（如「后端/前端/测试/大数据」），不传则用记忆里的 `target_role`。
- **读记忆**：`target_role`、`skill`。
- **内置静态岗位库**（`方向 → 所需技能`，软工应届生常见方向）：

| 方向 | 所需技能 |
|---|---|
| Java后端 | Java、SpringBoot、MySQL、Redis、并发/JVM、网络 |
| 前端 | JavaScript、Vue/React、HTML/CSS、HTTP/网络、浏览器 |
| 测试开发 | Java/Python、测试理论、自动化测试、SQL、Linux |
| 大数据 | Java/Scala、Hadoop、Spark、SQL、分布式 |
| 运维/DevOps | Linux、Docker、K8s、网络、Shell/Python、监控 |
| 客户端 | Java/Kotlin 或 Swift、移动框架、网络、数据结构 |
| 算法 | Python/C++、数据结构与算法、机器学习、数学 |

- **匹配逻辑**：对每个方向，计算「用户技能 ∩ 所需技能」的覆盖率作为匹配度，按匹配度降序；对每个方向列出「缺失技能」。
- **输出**：匹配度排序 + 各方向缺失技能清单 + 补强建议（模板文本，具体润色交 LLM）。

### 4.2 `ResumeImproveTool`（resume_improve）

- **描述**：基于用户技能/进度/目标岗位，用 STAR 法则生成简历要点或改写经历。
- **参数**：
  - `experience`（可选）：用户补充的一段项目/实习经历描述。
- **读记忆**：`skill`、`progress`、`target_role`。
- **逻辑**：用 STAR（情境 Situation / 任务 Task / 行动 Action / 结果 Result）四段模板，结合记忆里的技能与目标岗位填充要点；若传了 `experience`，则针对该经历产出结构化改写。
- **输出**：STAR 简历要点 + 量化表达建议（如「把『会 Java』改成『独立完成 XX 模块，接口 QPS 提升 Y%』」）。具体文案由 LLM 在最终答案补全。

### 4.3 `MistakeReviewTool`（mistake_review）

- **描述**：针对用户答错的面试题生成结构化复盘框架。
- **参数**：
  - `question`（必填）：题目。
  - `myAnswer`（可选）：用户当时的错误回答。
- **读记忆**：`skill`、`target_role`（用于关联知识盲区）。
- **逻辑**：生成复盘卡模板：
  1. 题目
  2. 我的错误（若有 `myAnswer` 则填入）
  3. 正确思路（留白，由 LLM 在最终答案补全）
  4. 知识盲区（关联记忆里的技能）
  5. 下次怎么做（行动项）
- **输出**：结构化复盘框架文本。

## 5. 架构与组件

### 5.1 涉及改动的文件

| 文件 | 动作 | 说明 |
|---|---|---|
| `tool/Tool.java` | 修改 | `execute` 加 `String userId` 首参 |
| `tool/StudyPlanTool.java` | 修改 | 签名加 `userId`（忽略） |
| `tool/InterviewQuestionTool.java` | 修改 | 签名加 `userId`（忽略） |
| `agent/AgentLoop.java` | 修改 | 调用点 `tool.execute(userId, params)` |
| `tool/JobMatchTool.java` | 新增 | 岗位匹配工具 |
| `tool/ResumeImproveTool.java` | 新增 | 简历优化工具 |
| `tool/MistakeReviewTool.java` | 新增 | 错题复盘工具 |
| `src/test/java/**` | 新增/修改 | 3 个新工具单测 + 老工具/主循环测试签名同步 |

### 5.2 依赖关系

```
新工具 ──注入──> MemoryService ──> UserMemoryStore ──> MySQL(user_memory)
AgentLoop ──execute(userId, params)──> ToolRegistry ──> Tool 实现
```

- 新工具通过 `@Component` + `@RequiredArgsConstructor` 注入 `MemoryService`，自动注册进 `ToolRegistry`，并自动出现在 `CotPromptBuilder` 给 LLM 的工具列表里（无需额外接线）。

## 6. 错误处理

- 记忆加载失败：`MemoryService.load` 内部已 catch，返回空列表 `List.of()`，工具退化为「无个人数据」的通用输出，不抛异常。
- 参数缺失：`params.getOrDefault` 兜底，沿用现有工具风格。
- 工具执行异常：由 `AgentLoop` 现有 try/catch 捕获，作为工具结果回填（「工具执行失败：xxx」）。

## 7. 测试策略

- 单测（mock 掉 `MemoryService`，喂固定 `List<UserMemory>`）：
  - `JobMatchTool`：给目标「Java后端」+ 技能「Java/MySQL」，断言输出包含「Java后端」匹配度靠前、缺失技能含「Redis/SpringBoot」等；传 `role` 覆盖默认方向时结果变化。
  - `ResumeImproveTool`：给技能/进度/目标，断言输出含 STAR 四段关键词；传 `experience` 时输出包含该经历内容。
  - `MistakeReviewTool`：给题目 + 错误回答，断言输出含「题目/正确思路/知识盲区/下次」结构。
  - 空记忆场景：三个工具在记忆为空时仍能返回有效文本，不抛异常。
- 回归：老工具单测、`AgentLoop` 测试、`ToolRegistry` 测试签名同步后全量 `mvn test` 绿。
- 手动验收：启动后页面输入对应指令，观察流式展示「思考 → 调用新工具 → 工具结果 → 最终答案」。

## 8. 验收标准

- 说「我想找 Java 后端工作」→ Agent 调用 `job_match`，输出匹配度与技能差距。
- 贴一段项目经历 → Agent 调用 `resume_improve`，产出 STAR 改写要点。
- 说「这道题我答错了：xxx」→ Agent 调用 `mistake_review`，产出结构化复盘，且最终答案补全了「正确思路」。
- 三个新工具都读取了记忆（输出中包含用户画像内容），证明「工具 + 记忆」闭环打通。
- 全量测试绿，老功能无回归。

## 9. 复盘疑难点（本切片相关）

- 身份判断 / 登录问题已记入复盘清单第 7 条；本切片沿用「匿名编号」身份，通过 `execute(userId, params)` 把身份传给工具。
