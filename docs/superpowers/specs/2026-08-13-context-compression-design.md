# 切片4：上下文压缩（长对话摘要）设计文档

**目标：** 长对话时，喂给 LLM 的历史不再全量，而是「滚动摘要 + 最近窗口原文」，token 封顶；数据库保留全部原文，前端仍可回看完整对话。

**背景：** 切片2 的 `chat_message` 已预留 `msg_type='summary'` 字段；切片3 解决了"用户级记忆"。但 `AgentLoop` 每次 `getMessages` 全量加载历史、无截断，对话一长 token 无限涨、成本与超限风险随长度上升。切片4 用滚动摘要压缩"喂给模型的那份历史"。

## 架构

- **三态标记**（复用 `chat_message.msg_type`，无需迁移）：
  - `normal`：未压缩原文，正常喂给模型、前端显示。
  - `summary`：系统生成的滚动摘要，喂给模型、不显示给前端。
  - `archived`：已被摘要覆盖的原文（content 保留），不再喂给模型、但前端仍显示。
- **滚动整体摘要**：每次压缩把「旧摘要 + 未压缩原文」重新浓缩成一段新摘要，删旧留新，摘要长度受 prompt 约束。
- **触发条件**：估算 token（以字符数近似，中文 1 字 ≈ 1 token）超过阈值。
- **保留窗口**：压缩时最近 N 条原文不压，保证最新对话零失真。
- **手搓实现**：新增 `ContextCompressor` 组件，不引入任何框架。

## 数据流

1. `AgentLoop.run` 加载：
   - `memoryService.load(userId)` 读长期记忆
   - `sessionStore.getLatestSummary(sessionId)` 读最新摘要（可能为空）
   - `sessionStore.getNormalMessages(sessionId)` 读未压缩原文
   - `promptBuilder.build(memories, summary)` 拼 system prompt
   - messages = [system] + [normal 原文] + [本轮 user input]
2. 正常 CoT 循环，产出 finalAnswer，`append` 本轮 user/assistant（msg_type=normal）。
3. `AgentLoop.run` 末尾调 `contextCompressor.compressIfNeeded(sessionId)`（尽力而为，失败仅记日志）。

### 压缩流程（compressIfNeeded）

1. `countNormalChars(sessionId)`：统计 normal 消息 content 字符总数（`CHAR_LENGTH`）。
2. 若 ≤ 阈值（默认 8000），直接返回。
3. 取「最新摘要（若有）+ 全部 normal 原文」→ 组 prompt → `llmClient.chat` 生成新摘要（纯文本）。
4. `replaceSummary(sessionId, newSummary)`：删旧 summary，插新 summary（role='system'，msg_type='summary'）。
5. `archiveOlder(sessionId, keepCount)`：把 normal 中除最近 keepCount（默认 10）条外，msg_type 更新为 `archived`。

## 数据库

- 复用 `chat_message.msg_type`（VARCHAR(16)，V1 已建，注释 'normal / summary'）。取值扩展为 `normal / summary / archived`，**无需迁移**（无 DB 约束，纯代码层取值）。
- 关键 SQL：
  - 统计字符：`SELECT COALESCE(SUM(CHAR_LENGTH(content)), 0) FROM chat_message WHERE session_id = ? AND msg_type = 'normal'`
  - 取最新摘要：`SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1`
  - 取未压缩原文：`SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id`
  - 归档：保留最近 keepCount 条 normal，其余 `UPDATE chat_message SET msg_type = 'archived'`（按 id 定位保留起点）
  - 前端历史：`SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type <> 'summary' ORDER BY id`

## 关键决策

1. **原文全保留**：压缩不删原文，只打 `archived` 标记。前端 `/api/chat/history` 返回 normal + archived（过滤 summary），用户可回看完整对话。
2. **滚动整体摘要（删旧留新）**：摘要不是用户原文，删旧留新不违背"保留原文"；摘要始终一段、长度可控。
3. **触发 = 估算 token（字符数）**：默认 8000 字符，中文 1 字 ≈ 1 token 的保守估计。不引入 tokenizer（DeepSeek tokenizer 非标准库，本地精确算不准且增依赖）。
4. **保留窗口 = 最近 10 条原文**：最新对话零失真。
5. **摘要尽力而为**：LLM 摘要失败仅记日志、不阻塞回答（与记忆抽取同套路）。
6. **手搓，不引框架**：`ContextCompressor` 自己实现，是简历卖点。
7. **摘要注入 system prompt**：摘要不单独作为一条消息，而是与记忆一起拼进主 system prompt（`CotPromptBuilder.build(memories, summary)`），避免多条 system 消息的兼容性问题。

## 组件与接口（供实现计划推导）

- `ChatMessage`：新增 `String msgType`（默认 `"normal"`）。保留原 2 参构造器语义（默认 normal），新增 3 参构造器。
- `SessionStore` 接口新增：
  - `List<ChatMessage> getNormalMessages(String sessionId)`
  - `String getLatestSummary(String sessionId)`
  - `int countNormalChars(String sessionId)`
  - `void replaceSummary(String sessionId, String content)`
  - `void archiveOlder(String sessionId, int keepCount)`
  - （`getMessages` 改为过滤 summary，返回 normal + archived）
- 新组件 `ContextCompressor`（`@Component`，依赖 `LlmClient` + `SessionStore`）：
  - `void compressIfNeeded(String sessionId)`
  - 常量 `THRESHOLD_CHARS = 8000`、`KEEP_RECENT = 10`
  - `String buildPrompt(String oldSummary, List<ChatMessage> normals)` + 摘要 system 提示词（要求：只保留关键事实/目标/进度/待办，不臆造，中文，控制在 ~300 字内）
- `AgentLoop`：注入 `ContextCompressor`；加载改用 `getNormalMessages` + `getLatestSummary`；`CotPromptBuilder.build` 增加 `summary` 参数；`run` 末尾调用 `compressIfNeeded`。
- `CotPromptBuilder.build(List<UserMemory> memories, String summary)`：摘要非空时追加"以下是之前对话的摘要：…"段。
- `MysqlSessionStore` / `InMemorySessionStore`：实现上述新方法。

## 测试策略

- `ContextCompressor`：Mockito 单测（阈值内不压、超阈值触发、LLM 失败不抛异常、摘要 prompt 正确、归档保留窗口正确）。
- `SessionStore`（MySQL/内存）：新查询/更新 SQL 正确性、msg_type 过滤、归档逻辑。
- `CotPromptBuilder`：摘要为空/非空两种情况的 prompt 拼接。
- `AgentLoop`：加载用 normal+summary、末尾触发压缩。
- 端到端手动验证：长对话（超阈值）后，新建消息时模型仍能基于摘要回答早期内容；前端仍能看到完整历史。

## 扩展性与演进路线（未来，非本切片）

1. **增量摘要**：若滚动整体摘要的"再摘要失真"成为问题，改为增量摘要拼接（只总结新增段，不重压旧摘要）。
2. **精确 token 计数**：接入 DeepSeek 官方 tokenizer 或 tiktoken 近似，替换字符数估算。
3. **摘要异步化**：与记忆抽取一样，挪到后台线程池，不阻塞回复。
4. **语义检索（RAG）**：记忆量增长后，向量化记忆做检索注入（依赖切片"记忆多值化"）。
5. **可观测**：把压缩动作（触发/阈值/摘要内容）记录到 `agent_step` 或日志，后台可查。
