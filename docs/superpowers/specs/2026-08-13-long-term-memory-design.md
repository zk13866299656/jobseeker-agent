# 切片3：长期记忆（跨会话自动抽取） 设计文档

**目标：** 让 Agent 在跨会话（新建对话）时仍能"记住用户"——称呼、目标岗位、技能栈、求职进度、偏好等稳定信息，实现从"会话级记忆"到"用户级记忆"的升级。

**背景：** 切片2 解决了"刷新/重启后同一会话历史恢复"，但会话仍是独立的"本子"，新建会话（新 sessionId）即失忆。切片3 用 `app_user` + `user_memory` 两张预留表，实现跨会话的用户级记忆。

## 架构

- **身份识别：** 前端 `localStorage` 新增稳定 `userId`（UUID，刷新/新建会话都不变），作为"这是同一个人"的标识。无登录，本机单用户。
- **记忆注入：** `AgentLoop` 启动时读该用户记忆，拼进系统提示，让模型"带上记忆"回答。
- **记忆抽取：** 每次对话回答之后，额外做一次 LLM 调用，从"已有记忆 + 最新一轮对话"中抽取新增/变更事实，写入 `user_memory`。
- **尽力而为：** 抽取失败只记日志、不阻断已给出的回答。

## 数据流

1. 前端请求 `/api/chat/stream?message=..&sessionId=..&userId=..`
2. `AgentLoop.run(sessionId, userId, message, sink)`：
   - `memoryService.load(userId)` 读记忆 → 拼进 system prompt
   - 正常跑 CoT 循环，产出 finalAnswer，并持久化 user+assistant 到 `chat_message`
3. Controller 把 final_answer 发给用户
4. Controller 调 `memoryService.extractAndStore(userId, sessionId, message, finalAnswer)`：
   - 读已有记忆 → 组 prompt（已有记忆 + 最新一轮对话）→ LLM 抽取 → 解析 JSON → upsert 进 `user_memory`
5. 发 done，结束

## 数据库

复用 V1 的 `app_user` + `user_memory`。新增迁移 **V2**：给 `user_memory` 加唯一键 `(user_id, memory_type)`，支持 upsert（同类信息覆盖更新）。

`memory_type` 封闭集合：`name`（称呼）/ `target_role`（目标岗位）/ `skill`（技能）/ `progress`（进度）/ `preference`（偏好）/ `fact`（其他）。

## 关键决策

1. **抽取时机：** 同步、每次回答后抽取（简单可靠、易验证）。不做后台异步 / 隔轮抽取（YAGNI）。
2. **身份：** localStorage 稳定 `userId`，无登录注册。
3. **upsert 而非追加：** 同类记忆覆盖更新，避免"先叫 Alice 后叫 Bob"记成两条矛盾记忆。
4. **抽取尽力而为：** 失败仅记日志，绝不影响用户已收到的回答。
5. **全量注入记忆：** 记忆量小，不做语义检索（YAGNI）。
6. **`chat_session.user_id` 暂不接线：** 保持本切片聚焦，留给后续"会话列表 / 后台管理"切片。

## 测试策略

- `UserMemoryStore` / `MemoryService`：Mockito 单测（SQL 正确、upsert、字段映射、getOrCreateUser）。
- `MemoryExtractor`：Mockito 单测（JSON 解析、空结果、格式容错）。
- 端到端手动验证：新会话报名字 → 新建会话 → 仍记得。

## 后续切片接口预留

- 语义检索 → 记忆量增大后再引入 embedding / 向量检索
- 记忆冲突裁决 / 衰减 / 手动编辑 → 后续
- 上下文压缩 → `chat_message.msg_type = 'summary'`
