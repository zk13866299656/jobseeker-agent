# 切片2：会话持久化与记忆恢复 设计文档

**目标：** 将 Agent 的会话历史从内存持久化到 MySQL，使对话在页面刷新、应用重启后仍能恢复，解决切片1"刷新就忘"的问题。

**背景：** 切片1 的会话历史存于内存（`InMemorySessionStore`），且前端每次刷新生成新 `sessionId`（`demo-时间戳`），导致刷新即失忆。

## 架构

- **存储层：** 本地 MySQL 9.2.0，`JdbcTemplate` 手写 SQL，Flyway 版本化迁移。
- **抽象：** 沿用 `SessionStore` 接口，新增 `MysqlSessionStore` 实现；`AgentLoop` 主循环**零改动**。
- **前端：** `sessionId` 存 `localStorage`，刷新沿用；页面加载时拉取历史渲染。

## 数据库

`jobagent` 库 5 张表（`app_user` / `chat_session` / `chat_message` / `user_memory` / `agent_step`），见 `src/main/resources/db/migration/V1__init.sql`（已建好）。

切片2 实际使用：`chat_session` + `chat_message`。其余三张为后续切片（长期记忆 / 后台管理 / 反思）预留。

## 关键决策

1. **JdbcTemplate 而非 JPA/MyBatis：** 延续项目"手搓、懂原理"定位，手写 SQL 展示对数据库交互的理解。
2. **Flyway baseline：** 表已手动建好，配置 `baseline-on-migrate: true` + `baseline-version: 1`，让 Flyway 跳过 V1 避免重复建表冲突；后续切片用 V2、V3…。
3. **密码安全：** MySQL 密码走 `MYSQL_PASSWORD` 环境变量，不硬编码进 `application.yml`（仓库公开）。
4. **Bean 切换：** 去掉 `InMemorySessionStore` 的 `@Component`（保留为测试用纯类），`MysqlSessionStore` 成为唯一生产 `SessionStore` bean。

## 测试策略

- `MysqlSessionStore` 用 Mockito 单测（验证 SQL 正确、映射正确、先 upsert 会话再插消息的顺序）。
- 端到端用本地 MySQL 手动验证：发消息 → 刷新页面 → 历史恢复 → 重启应用 → 仍恢复。

## 后续切片接口预留

- 长期记忆 → `app_user` + `user_memory`
- 上下文压缩 → `chat_message.msg_type = 'summary'`
- 调试/后台管理 → `agent_step`
