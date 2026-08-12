# 切片 1：Agent 核心引擎最小垂直切片 — 设计文档

日期：2026-08-13
仓库：`jobseeker-agent`
技术栈（SRS 强制锁定）：SpringBoot3 + Maven + Java17

---

## 1. 背景与目标

本项目是「面向软工应届生的求职规划智能 Agent 系统」，核心卖点是**纯 Java 自研 Agent 调度**——不依赖 SpringAI/LangChain 黑盒，自己实现 CoT 思维链、任务拆解、多工具编排、记忆与反思。

本切片（切片 1）的目标是**用最小代价端到端跑通 Agent 主循环**，验证「自然语言指令 → 自主拆解 → 调工具 → 出结果」的核心闭环，为后续横向扩展（记忆、压缩、反思、RAG、更多工具、持久化）打好地基。

## 2. 范围

### 2.1 本切片做什么

- SpringBoot3 + Java17 + Maven 工程骨架
- LLM 适配层（可配置切换，先 DeepSeek，OpenAI 兼容 HTTP 直调）
- 自研 Agent 主循环 + CoT 结构化 JSON 解析器
- 统一 Tool 接口 + 工具注册表 + 2 个演示工具
- 内存会话上下文（`ConcurrentHashMap`，预留接口，后续换 Redis/MySQL）
- SSE 流式输出 + 极简 Web 对话页

### 2.2 本切片不做什么（留待后续切片）

- MySQL / Redis 持久化
- 向量库 / RAG 知识库
- 上下文压缩算法
- 反思修正子 Agent
- 剩余 4 个工具（岗位筛选、简历优化、错题复盘、文件导出）
- 管理员后台、Knife4j 接口文档（后续切片补）

## 3. 关键技术决策

### 3.1 LLM 接入（可配置切换）

- 不引入官方 SDK，直接用 HTTP 调 OpenAI 兼容的 `/chat/completions` 端点。
- DeepSeek 与通义千问均提供 OpenAI 兼容端点，切换只改配置里的 `base-url / api-key / model`。
- 配置载体：`application.yml` + 环境变量覆盖。**API Key 从环境变量读取，不硬编码、不入库、不进 git**。

配置项（`LlmProperties`，`@ConfigurationProperties`）：

| 键 | 说明 | 默认 |
|----|------|------|
| `llm.base-url` | OpenAI 兼容端点 | `https://api.deepseek.com` |
| `llm.api-key` | 从 `DEEPSEEK_API_KEY` 环境变量读 | — |
| `llm.model` | 模型名 | `deepseek-chat` |
| `llm.temperature` | 采样温度（CoT 解析建议低） | `0.3` |

### 3.2 工具调用机制：自研 CoT 结构化解析（方案 B）

不采用原生 function calling。通过 system prompt 约束模型输出**固定 JSON**，Java 端解析：

- 调用工具：
  ```json
  {"thinking": "...", "tool": "study_plan", "params": {"targetJob": "...", "days": 30}}
  ```
- 结束并给出最终答案：
  ```json
  {"thinking": "...", "final_answer": "..."}
  ```

解析产物 `CotResult` 三态：`tool_call`（含工具名+参数）/ `final_answer` / 解析失败。

健壮性策略：严格 JSON Schema 提示 + 低温度 + 解析失败重试（最多 2 次，提示模型输出合法 JSON）。

### 3.3 会话存储（内存版）

- 定义 `SessionStore` 接口（`getMessages / append / create`）。
- 本切片用 `InMemorySessionStore`（`ConcurrentHashMap<String, List<ChatMessage>>`）。
- 接口抽象保证后续切片可平滑替换为 Redis（短期）+ MySQL（长期），不改上层调用。

### 3.4 流式输出（SSE）

- `ChatController` 返回 `SseEmitter`，按阶段推送事件。
- 事件类型：`thinking`、`tool_call`、`tool_result`、`final_answer`、`error`、`done`。
- 好处：Agent 的思考与工具调用过程可实时、显式地展示，直观体现「自研思维链」卖点。

### 3.5 前端

- 极简单页：`static/index.html` + `static/app.js`，SpringBoot 静态资源托管，无框架。
- 功能：输入框 + 发送 → 用 `EventSource`/`fetch` 流式渲染「思考 / 工具调用 / 结果 / 最终答案」。

## 4. 架构与组件

### 4.1 包结构

```
com.jobagent
├── JobAgentApplication.java
├── common/          # Result 统一返回体、BizException、GlobalExceptionHandler
├── config/          # LlmProperties（@ConfigurationProperties）
├── llm/             # LlmClient 接口 + DeepSeekClient 实现 + ChatMessage
├── agent/           # AgentLoop 主循环、AgentContext、AgentStep
├── agent/cot/       # CotParser、CotResult、系统提示词模板
├── tool/            # Tool 接口、ToolRegistry、StudyPlanTool、InterviewQuestionTool
├── session/         # SessionStore 接口 + InMemorySessionStore 实现
└── web/             # ChatController（SSE）、请求/响应 DTO
resources/
├── application.yml
└── static/          # index.html + app.js
```

### 4.2 组件职责

| 组件 | 职责 |
|------|------|
| `LlmClient` | 抽象「发消息→拿原始回复」；`DeepSeekClient` 用 `WebClient` 直调 |
| `CotParser` | 把模型输出解析为 `CotResult`；处理 JSON 提取、非法格式、失败重试 |
| `AgentLoop` | 自研 while 循环：思考→解析→执行工具→回填→判断是否结束；emit SSE 事件 |
| `AgentContext` | 单次任务运行期状态（历史消息、已执行步骤、中间结果） |
| `AgentStep` | 单步记录（思考、工具名、参数、结果），用于留痕 |
| `Tool` 接口 | `name / description / parametersSchema / execute(params)` |
| `ToolRegistry` | 注册与按名查找工具（`Map`） |
| `StudyPlanTool` | 输入目标岗位+薄弱点+天数 → 模板生成日/周学习计划 |
| `InterviewQuestionTool` | 输入知识点 → 返回内置 Java/SpringBoot/Redis 面试题 |
| `SessionStore` | 会话消息读写抽象 |

> 放 2 个工具（而非 1 个）是为了让 Agent 真正需要「选择」工具，从而验证工具选择逻辑。

## 5. 主循环数据流

```
用户指令 → ChatController(SSE)
  → AgentLoop.run(指令, sessionId)
    1. 从 SessionStore 加载历史消息
    2. 组装 messages：system prompt（角色 + 工具列表 JSON + 输出格式约束）
       + 历史消息 + 当前用户指令
    3. while (未结束 && 步数 < MAX_STEPS=10):
       a. 调 LLM 输出 CoT JSON → emit "thinking"
       b. CotParser 解析：
          - final_answer → emit "final_answer"，结束循环
          - tool_call → emit "tool_call"
            → ToolRegistry 查找并执行 → emit "tool_result"
            → 结果作为 assistant/tool 消息回填上下文，继续循环
          - 解析失败 → 重试，仍失败则兜底
    4. 返回最终答案 + 执行步骤日志，emit "done"
```

## 6. 错误处理

| 场景 | 处理 |
|------|------|
| LLM 调用失败 | 重试 2 次 → 抛 `BizException` → 全局异常处理器 → 友好提示 |
| CoT JSON 解析失败 | 重试 2 次（提示模型输出合法 JSON）→ 仍失败则原文兜底返回 |
| 工具执行抛异常 | 捕获，作为工具结果回填（"工具执行失败：xxx"），让 Agent 自行修正 |
| 未知工具名 | 回填错误信息，让 Agent 重新选择 |
| 超步数（>10 步） | 强制结束，返回已聚合结果 |

统一返回体 `Result<T>`；`GlobalExceptionHandler` 兜底，无 500 裸异常。

## 7. 测试策略

- 单测：
  - `CotParser`：合法 JSON / 非法 JSON / 缺失字段 / 带思考文本前缀的边界情况
  - `ToolRegistry`：注册、查找、未知工具
  - `AgentLoop`：mock `LlmClient` 返回预设 CoT，验证循环终止、步数上限、工具调用次数与顺序
  - `StudyPlanTool` / `InterviewQuestionTool`：入参正确时输出结构符合预期
- 手动验收：启动后打开页面，输入复杂指令，观察流式展示各阶段。

## 8. 验收标准

- 页面能输入指令，流式看到「思考 → 工具调用 → 结果 → 最终答案」。
- 复杂指令（如「帮我制定一个 30 天 Java 后端求职学习计划」）能被拆解并调用 `study_plan` 工具。
- 换一条指令（如「问几道 SpringBoot 面试题」）能正确选择 `interview_question` 工具，验证工具选择。
- 配置里换成通义的 `base-url / api-key / model` 后同样可跑（验证可配置切换，预留）。

## 9. 后续切片规划（简要，另行各自出 spec）

1. 记忆系统：短期（Redis 10 轮）+ 长期（MySQL 用户画像/短板/投递记录）
2. 上下文压缩算法
3. 反思修正子 Agent
4. 剩余 4 个工具
5. 垂直 RAG 知识库（文档切片、向量化、检索、溯源）
6. 会话管理持久化 + 留痕重放
7. 管理员后台 + Knife4j 接口文档 + Docker 部署
