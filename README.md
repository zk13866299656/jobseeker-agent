# jobseeker-agent · 求职规划智能 Agent

面向**软件工程应届生求职场景**的智能 Agent 助手。核心特色：**纯 Java 手搓 Agent 调度**，不依赖 SpringAI / LangChain 等框架黑盒，自主实现思维链（CoT）、多工具编排、长期记忆、上下文压缩。

用户用自然语言和它对话，它就能：记住你是谁、记住你们聊到哪、并主动调用工具帮你做岗位匹配、简历优化、错题复盘、学习规划、面试出题。

## 功能特性

- **流式对话**：SSE 流式输出，边思考边展示「思考 → 调用工具 → 工具结果 → 最终回答」全过程。
- **长期记忆**：每次回答后 LLM 自动抽取稳定事实（称呼/目标岗位/技能/进度/偏好等）存入 MySQL，跨会话记住「你是谁」。
- **会话持久化**：对话历史落库，刷新/重启不丢，自动恢复历史。
- **上下文压缩**：长对话自动摘要，早期原文归档保留、最近消息原文保留，解决长会话 Token 溢出。
- **插件式工具**：5 个工具，其中 3 个读取长期记忆做个性化（岗位匹配 / 简历优化 / 错题复盘）。
- **执行轨迹留痕**：每次思考、工具调用、结果全部入库，可回看 Agent 决策链路。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 · Java 17 · Maven · Lombok |
| Agent 调度 | **自研** CoT 思维链 + 工具编排循环（不用 SpringAI / LangChain） |
| 持久层 | Spring JDBC（JdbcTemplate）· Flyway 迁移 · MySQL 8+ |
| 大模型 | DeepSeek（deepseek-chat），RestTemplate 直接调 HTTP API（无 SDK 依赖） |
| 前端 | 原生 HTML + JavaScript + SSE |

> 说明：项目刻意**不引入** MyBatis-Plus、Redis、向量库等重组件——用 JdbcTemplate 直连 MySQL、单机内存即可跑通，依赖极简，聚焦「自研 Agent 调度」这一核心。

## 核心架构

```
前端对话层 → Controller → Agent 核心调度层（自研） → 工具插件层 → 数据持久层
              (SSE)        AgentLoop / ContextCompressor   ToolRegistry    MySQL
```

**Agent 主循环（核心）**：

1. 用户输入 + 历史消息 + 长期记忆 + 压缩摘要 → 拼成上下文发给 LLM。
2. LLM 输出结构化 JSON：`{thinking, tool, params}` 或 `{thinking, final_answer}`。
3. 解析 JSON（`CotParser`）——若是工具调用，`ToolRegistry` 找到对应工具执行；若是最终答案，直接返回。
4. 工具结果回填进对话，继续循环，直到 LLM 给出最终答案（最多 10 步）。

工具通过 `@Component` 自动注册进 `ToolRegistry`，新增工具只需实现 `Tool` 接口（`name` / `description` / `parametersSchema` / `execute`），**无需改动核心调度代码**。

## 快速开始

### 环境要求

- JDK 17
- Maven 3.8+
- MySQL 8+（本地已启动）
- DeepSeek API Key

### 配置

通过环境变量注入（`application.yml` 里读取）：

| 环境变量 | 说明 |
|---|---|
| `MYSQL_PASSWORD` | MySQL 密码 |
| `DEEPSEEK_API_KEY` | DeepSeek API Key |

数据库连接默认 `jdbc:mysql://localhost:3306/jobagent`（账号 `root`），可在 `application.yml` 修改。**无需手动建表**——启动时 Flyway 自动执行 `db/migration` 下的 V1/V2 脚本建库建表。

### 启动

```bash
# Windows PowerShell
$env:MYSQL_PASSWORD="你的密码"; $env:DEEPSEEK_API_KEY="sk-xxx"

mvn spring-boot:run
```

浏览器打开 `http://localhost:8080`，即可对话。

### 运行测试

```bash
mvn test
```

## 内置工具

| 工具名 | 标识 | 功能 | 读记忆 |
|---|---|---|---|
| 学习计划 | `study_plan` | 按目标岗位/薄弱点/剩余天数生成分阶段学习计划 | 否 |
| 面试出题 | `interview_question` | 返回 Java/SpringBoot/Redis 高频面试题 | 否 |
| 岗位匹配 | `job_match` | 按技能匹配软工应届生岗位方向，输出匹配度 + 缺失技能 | 是 |
| 简历优化 | `resume_improve` | 用 STAR 法则改写经历为简历要点 | 是 |
| 错题复盘 | `mistake_review` | 针对答错的题生成结构化复盘卡 | 是 |

## 数据模型

5 张核心表（Flyway 自动建）：

| 表 | 用途 |
|---|---|
| `app_user` | 用户（身份 = 前端 localStorage 生成的匿名编号，非登录） |
| `chat_session` | 会话 |
| `chat_message` | 消息（`msg_type`：normal / summary / archived） |
| `user_memory` | 长期记忆（唯一键 `(user_id, memory_type)`，同类 upsert 覆盖） |
| `agent_step` | Agent 执行轨迹（思考/工具/结果留痕） |

长期记忆类型（封闭 6 类）：`name`（称呼）、`target_role`（目标岗位）、`skill`（技能）、`progress`（求职进度）、`preference`（偏好）、`fact`（其他稳定事实）。

## 项目结构

```
src/main/java/com/jobagent/
├── agent/          # Agent 核心：AgentLoop（主循环）、ContextCompressor（压缩）、AgentStep
│   └── cot/        # CoT：CotPromptBuilder（提示构建）、CotParser（JSON 解析）
├── llm/            # LLM 客户端：LlmClient 接口 + DeepSeekClient 实现（带 3 次重试）
├── memory/         # 长期记忆：MemoryExtractor（LLM 抽取）、MemoryService、UserMemoryStore
├── session/        # 会话存储：SessionStore 接口 + MysqlSessionStore / InMemorySessionStore
├── tool/           # 工具：Tool 接口 + ToolRegistry + 5 个工具实现
├── web/            # ChatController：SSE 流式对话 + 历史查询
└── config/         # AppConfig、LlmProperties

src/main/resources/
├── static/         # 前端：index.html + app.js（SSE 消费）
└── db/migration/   # Flyway：V1 建表、V2 记忆唯一键
```

## 开发历程（切片式推进）

| 切片 | 内容 |
|---|---|
| 1 | Agent 核心引擎：CoT 主循环 + 2 个工具 + SSE 对话页 |
| 2 | 会话持久化：历史存 MySQL，刷新恢复 |
| 3 | 长期记忆：LLM 自动抽取用户画像，跨会话记忆 |
| 4 | 上下文压缩：长对话滚动摘要，解决 Token 溢出 |
| 5 | 工具集扩展：3 个记忆驱动工具（岗位匹配/简历优化/错题复盘） |

## 项目亮点（简历可讲）

1. **纯手搓 Agent 调度**：不用 SpringAI / LangChain，手动实现「思考→选工具→执行→汇总→循环」的主循环 + CoT 结构化输出解析。
2. **长期记忆设计**：唯一键 `(user_id, memory_type)` + 封闭 6 类 + 同类 upsert 覆盖，保证记忆表不随对话膨胀（每人最多 6 行）。
3. **自研上下文压缩**：字符数阈值触发 + 保留窗口 + 原文全保留只对模型压缩，早期内容靠摘要独立回忆。
4. **工具读记忆个性化**：工具注入 MemoryService 读用户画像，产出匹配度、STAR 要点、复盘卡等个性化结果。
5. **插件式架构**：新增工具实现接口即可，符合开闭原则。

## 未实现（设计时主动推迟）

- 登录注册 / 多用户隔离（当前用浏览器匿名编号认人）
- 文件导出（学习计划/简历下载）
- 真实招聘数据源（岗位匹配用内置静态岗位库）
- 向量检索 / RAG 知识库、反思子 Agent
