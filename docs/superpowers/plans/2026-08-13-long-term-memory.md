# 切片3：长期记忆（跨会话自动抽取） 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Agent 在新建会话后仍能"记住用户"——从每轮对话自动抽取稳定事实（称呼/目标岗位/技能/进度/偏好）存进 MySQL，并在新会话启动时注入系统提示。

**Architecture:** 新增 `memory` 包（`UserMemory` 模型 + `UserMemoryStore` JDBC 存储 + `MemoryExtractor` LLM 抽取 + `MemoryService` 编排）；`AgentLoop.run` 增加 `userId` 参数并注入记忆；`ChatController` 回答后调 `extractAndStore`（尽力而为，失败不阻断）；前端 localStorage 加稳定 `userId`。复用 V1 的 `app_user`/`user_memory` 两张表，新增 V2 迁移加唯一键。

**Tech Stack:** Spring Boot 3.3.5 + Spring JDBC（JdbcTemplate 手写 SQL）+ Flyway + MySQL 9.2 + DeepSeek（deepseek-chat）+ 原生 JS。

**Spec:** `docs/superpowers/specs/2026-08-13-long-term-memory-design.md`

## Global Constraints

- 纯手搓，不引入任何 Agent/LLM 框架（LangChain / langchain4j / Spring AI 一律不用）。
- 持久层用 `JdbcTemplate` 手写 SQL，不用 JPA/MyBatis。
- 数据库密码走 `MYSQL_PASSWORD` 环境变量、API key 走 `DEEPSEEK_API_KEY`，不硬编码。
- `memory_type` 封闭集合：`name` / `target_role` / `skill` / `progress` / `preference` / `fact`（覆盖 V1 表注释里的旧集合，仅注释差异，不需迁移）。
- 记忆抽取"尽力而为"：任何失败只记日志（`log.warn`），绝不阻断已给出的回答。
- `chat_session.user_id` 本切片不接线（留给后续"会话列表/后台管理"切片）。
- 前端身份：`localStorage` 稳定 `userId`（UUID），无登录注册，本机单用户。

---

## 前置说明

- `app_user`、`user_memory` 两张表已在 `V1__init.sql` 建好并提交，本切片只新增 V2 迁移加唯一键。
- Flyway 已配 `baseline-on-migrate: true` + `baseline-version: 1`，V2 会在下次启动时自动应用。
- `user_memory` 目前无数据，加唯一键不会冲突。若 Flyway 因历史重复数据报错，先 `TRUNCATE TABLE user_memory;`（开发库该表本来就没数据）。
- `InMemorySessionStore` 保留（测试用），`SessionStore` 接口不动。

---

### Task 1: V2 迁移 + UserMemory 模型 + UserMemoryStore（TDD）

**Files:**
- Create: `src/main/resources/db/migration/V2__user_memory_unique_key.sql`
- Create: `src/main/java/com/jobagent/memory/UserMemory.java`
- Create: `src/main/java/com/jobagent/memory/UserMemoryStore.java`
- Test: `src/test/java/com/jobagent/memory/UserMemoryStoreTest.java`

**Interfaces:**
- Produces: `UserMemoryStore`（`@Component`）：`Long findUserId(String userId)`、`long getOrCreateUser(String userId)`、`List<UserMemory> load(long appUserId)`、`void upsert(long appUserId, String type, String content, String sessionId)`。`UserMemory`（`@Data`）字段 `String type`、`String content`。

- [ ] **Step 1: 写失败测试**

`UserMemoryStoreTest.java`：

```java
package com.jobagent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryStoreTest {

    private static final String SELECT_USER_ID = "SELECT id FROM app_user WHERE username = ?";
    private static final String INSERT_USER = "INSERT INTO app_user (username) VALUES (?)";
    private static final String SELECT_MEMORIES = "SELECT memory_type, content FROM user_memory WHERE user_id = ? ORDER BY id";
    private static final String UPSERT_MEMORY =
            "INSERT INTO user_memory (user_id, memory_type, content, source_session_id) VALUES (?, ?, ?, ?) AS new " +
            "ON DUPLICATE KEY UPDATE content = new.content, source_session_id = new.source_session_id, updated_at = NOW()";

    @Test
    void findUserIdReturnsNullWhenNoRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of());
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertNull(store.findUserId("u1"));
    }

    @Test
    void findUserIdReturnsIdWhenExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of(5L));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertEquals(5L, store.findUserId("u1"));
    }

    @Test
    void getOrCreateUserReturnsExistingWithoutInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of(5L));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertEquals(5L, store.getOrCreateUser("u1"));
        verify(jdbc, never()).update(eq(INSERT_USER), eq("u1"));
    }

    @Test
    void getOrCreateUserInsertsWhenMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of(), List.of(6L));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertEquals(6L, store.getOrCreateUser("u1"));
        verify(jdbc).update(eq(INSERT_USER), eq("u1"));
    }

    @Test
    void loadMapsRowsToMemories() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_MEMORIES), ArgumentMatchers.<RowMapper<UserMemory>>any(), eq(5L)))
                .thenReturn(List.of(new UserMemory("name", "张三"), new UserMemory("target_role", "Java后端")));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        List<UserMemory> result = store.load(5L);
        assertEquals(2, result.size());
        assertEquals("name", result.get(0).getType());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void upsertCallsUpdateWithAllArgs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserMemoryStore store = new UserMemoryStore(jdbc);
        store.upsert(5L, "name", "张三", "s1");
        verify(jdbc).update(eq(UPSERT_MEMORY), eq(5L), eq("name"), eq("张三"), eq("s1"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=UserMemoryStoreTest test`
Expected: 编译失败（`UserMemoryStore`、`UserMemory` 不存在）

- [ ] **Step 3: 建 V2 迁移**

`src/main/resources/db/migration/V2__user_memory_unique_key.sql`：

```sql
-- V2: 给 user_memory 加唯一键 (user_id, memory_type)，支持同类记忆 upsert 覆盖
ALTER TABLE user_memory
    ADD UNIQUE KEY uk_user_memory_type (user_id, memory_type);
```

- [ ] **Step 4: 建 UserMemory 模型**

`src/main/java/com/jobagent/memory/UserMemory.java`：

```java
package com.jobagent.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserMemory {
    private String type;
    private String content;
}
```

- [ ] **Step 5: 实现 UserMemoryStore**

`src/main/java/com/jobagent/memory/UserMemoryStore.java`：

```java
package com.jobagent.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UserMemoryStore {

    private static final String SELECT_USER_ID =
            "SELECT id FROM app_user WHERE username = ?";
    private static final String INSERT_USER =
            "INSERT INTO app_user (username) VALUES (?)";
    private static final String SELECT_MEMORIES =
            "SELECT memory_type, content FROM user_memory WHERE user_id = ? ORDER BY id";
    private static final String UPSERT_MEMORY =
            "INSERT INTO user_memory (user_id, memory_type, content, source_session_id) VALUES (?, ?, ?, ?) AS new " +
            "ON DUPLICATE KEY UPDATE content = new.content, source_session_id = new.source_session_id, updated_at = NOW()";

    private final JdbcTemplate jdbcTemplate;

    public Long findUserId(String userId) {
        List<Long> ids = jdbcTemplate.query(SELECT_USER_ID, (rs, n) -> rs.getLong("id"), userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long getOrCreateUser(String userId) {
        Long id = findUserId(userId);
        if (id != null) {
            return id;
        }
        jdbcTemplate.update(INSERT_USER, userId);
        return findUserId(userId);
    }

    public List<UserMemory> load(long appUserId) {
        return jdbcTemplate.query(SELECT_MEMORIES,
                (rs, n) -> new UserMemory(rs.getString("memory_type"), rs.getString("content")),
                appUserId);
    }

    public void upsert(long appUserId, String type, String content, String sessionId) {
        jdbcTemplate.update(UPSERT_MEMORY, appUserId, type, content, sessionId);
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -q -Dtest=UserMemoryStoreTest test`
Expected: 全部 PASS

- [ ] **Step 7: 提交**

```bash
git add src/main/resources/db/migration/V2__user_memory_unique_key.sql src/main/java/com/jobagent/memory/ src/test/java/com/jobagent/memory/
git commit -m "feat: 新增 UserMemoryStore 与 V2 迁移，长期记忆 upsert 落库"
```

---

### Task 2: MemoryExtractor（TDD）

**Files:**
- Create: `src/main/java/com/jobagent/memory/MemoryExtractor.java`
- Test: `src/test/java/com/jobagent/memory/MemoryExtractorTest.java`

**Interfaces:**
- Consumes: `LlmClient.chat(List<ChatMessage>)`（已有）、`UserMemory`（Task 1）、Spring 注入的 `ObjectMapper`（已有 bean）。
- Produces: `MemoryExtractor`（`@Component`）：`List<UserMemory> extract(List<UserMemory> existing, String userMessage, String assistantAnswer)`；包私有 `List<UserMemory> parse(String raw)`（供测试直调）。

- [ ] **Step 1: 写失败测试**

`MemoryExtractorTest.java`：

```java
package com.jobagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class MemoryExtractorTest {

    private MemoryExtractor newExtractor(LlmClient llm) {
        return new MemoryExtractor(llm, new ObjectMapper());
    }

    @Test
    void parseArrayReturnsMemories() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        List<UserMemory> result = e.parse(
                "[{\"type\":\"name\",\"content\":\"张三\"},{\"type\":\"skill\",\"content\":\"Java\"}]");
        assertEquals(2, result.size());
        assertEquals("name", result.get(0).getType());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void parseEmptyArrayReturnsEmpty() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        assertTrue(e.parse("[]").isEmpty());
    }

    @Test
    void parseObjectNotArrayReturnsEmpty() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        assertTrue(e.parse("{\"type\":\"name\",\"content\":\"张三\"}").isEmpty());
    }

    @Test
    void parseInvalidJsonReturnsEmpty() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        assertTrue(e.parse("这不是JSON").isEmpty());
    }

    @Test
    void parseSkipsBlankEntries() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        List<UserMemory> result = e.parse(
                "[{\"type\":\"name\",\"content\":\"张三\"},{\"type\":\"\",\"content\":\"\"}]");
        assertEquals(1, result.size());
    }

    @Test
    void extractBuildsPromptAndParsesResult() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("[{\"type\":\"name\",\"content\":\"张三\"}]");
        MemoryExtractor e = newExtractor(llm);
        List<UserMemory> result = e.extract(List.of(), "我叫张三", "你好张三");
        assertEquals(1, result.size());
        assertEquals("张三", result.get(0).getContent());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MemoryExtractorTest test`
Expected: 编译失败（`MemoryExtractor` 不存在）

- [ ] **Step 3: 实现 MemoryExtractor**

`src/main/java/com/jobagent/memory/MemoryExtractor.java`：

```java
package com.jobagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractor {

    private static final String EXTRACTOR_SYSTEM = """
            你是一个记忆抽取器。从对话中抽取关于用户的稳定事实，用于跨会话记忆。
            只输出一个 JSON 数组，每个元素是 {"type": "...", "content": "..."}。
            type 只能是以下之一：name（称呼）、target_role（目标岗位）、skill（技能）、progress（求职进度）、preference（偏好）、fact（其他稳定事实）。
            要求：
            - 只抽取稳定、可长期复用的信息（称呼、目标、技能、进度、偏好等），不抽取临时请求。
            - 若本次对话没有新的稳定信息，输出空数组 []。
            - 若用户纠正了之前的说法，用新说法覆盖旧说法。
            - 不要输出 JSON 以外的任何文字。
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public List<UserMemory> extract(List<UserMemory> existing, String userMessage, String assistantAnswer) {
        String raw = llmClient.chat(List.of(
                new ChatMessage("system", EXTRACTOR_SYSTEM),
                new ChatMessage("user", buildPrompt(existing, userMessage, assistantAnswer))));
        return parse(raw);
    }

    List<UserMemory> parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            if (!root.isArray()) {
                return List.of();
            }
            List<UserMemory> result = new ArrayList<>();
            for (JsonNode node : root) {
                String type = node.path("type").asText("");
                String content = node.path("content").asText("");
                if (!type.isBlank() && !content.isBlank()) {
                    result.add(new UserMemory(type, content));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆抽取解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(List<UserMemory> existing, String userMessage, String assistantAnswer) {
        StringBuilder sb = new StringBuilder();
        sb.append("已有记忆：\n");
        if (existing == null || existing.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (UserMemory m : existing) {
                sb.append("- [").append(m.getType()).append("] ").append(m.getContent()).append("\n");
            }
        }
        sb.append("\n最新一轮对话：\n");
        sb.append("用户：").append(userMessage).append("\n");
        sb.append("助手：").append(assistantAnswer).append("\n");
        return sb.toString();
    }

    private String extractJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end >= start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=MemoryExtractorTest test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/jobagent/memory/MemoryExtractor.java src/test/java/com/jobagent/memory/MemoryExtractorTest.java
git commit -m "feat: 新增 MemoryExtractor，用 LLM 抽取稳定记忆并解析 JSON"
```

---

### Task 3: MemoryService（TDD）

**Files:**
- Create: `src/main/java/com/jobagent/memory/MemoryService.java`
- Test: `src/test/java/com/jobagent/memory/MemoryServiceTest.java`

**Interfaces:**
- Consumes: `UserMemoryStore`（Task 1）、`MemoryExtractor`（Task 2）。
- Produces: `MemoryService`（`@Component`）：`List<UserMemory> load(String userId)`（尽力而为，失败返回空列表）、`void extractAndStore(String userId, String sessionId, String userMessage, String assistantAnswer)`（尽力而为，失败仅记日志）。

- [ ] **Step 1: 写失败测试**

`MemoryServiceTest.java`：

```java
package com.jobagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    @Test
    void loadReturnsEmptyWhenUserNotExist() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.findUserId("u1")).thenReturn(null);
        MemoryService service = new MemoryService(store, extractor);
        assertTrue(service.load("u1").isEmpty());
        verify(store, never()).load(anyLong());
    }

    @Test
    void loadReturnsMemories() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.findUserId("u1")).thenReturn(5L);
        when(store.load(5L)).thenReturn(List.of(new UserMemory("name", "张三")));
        MemoryService service = new MemoryService(store, extractor);
        List<UserMemory> result = service.load("u1");
        assertEquals(1, result.size());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void loadSwallowsException() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.findUserId("u1")).thenThrow(new RuntimeException("db down"));
        MemoryService service = new MemoryService(store, extractor);
        assertTrue(service.load("u1").isEmpty());
    }

    @Test
    void extractAndStoreOrchestrates() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.getOrCreateUser("u1")).thenReturn(5L);
        when(store.load(5L)).thenReturn(List.of(new UserMemory("name", "张三")));
        when(extractor.extract(anyList(), eq("我叫李四"), eq("你好李四")))
                .thenReturn(List.of(new UserMemory("name", "李四"), new UserMemory("target_role", "Java后端")));
        MemoryService service = new MemoryService(store, extractor);
        service.extractAndStore("u1", "s1", "我叫李四", "你好李四");
        verify(store).upsert(5L, "name", "李四", "s1");
        verify(store).upsert(5L, "target_role", "Java后端", "s1");
    }

    @Test
    void extractAndStoreSwallowsException() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.getOrCreateUser("u1")).thenThrow(new RuntimeException("db down"));
        MemoryService service = new MemoryService(store, extractor);
        service.extractAndStore("u1", "s1", "x", "y");
        verify(store, never()).upsert(anyLong(), anyString(), anyString(), anyString());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MemoryServiceTest test`
Expected: 编译失败（`MemoryService` 不存在）

- [ ] **Step 3: 实现 MemoryService**

`src/main/java/com/jobagent/memory/MemoryService.java`：

```java
package com.jobagent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryStore store;
    private final MemoryExtractor extractor;

    public List<UserMemory> load(String userId) {
        try {
            Long appUserId = store.findUserId(userId);
            if (appUserId == null) {
                return List.of();
            }
            return store.load(appUserId);
        } catch (Exception e) {
            log.warn("记忆加载失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void extractAndStore(String userId, String sessionId, String userMessage, String assistantAnswer) {
        try {
            long appUserId = store.getOrCreateUser(userId);
            List<UserMemory> existing = store.load(appUserId);
            List<UserMemory> extracted = extractor.extract(existing, userMessage, assistantAnswer);
            for (UserMemory m : extracted) {
                store.upsert(appUserId, m.getType(), m.getContent(), sessionId);
            }
        } catch (Exception e) {
            log.warn("记忆抽取失败（不影响回答）: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=MemoryServiceTest test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/jobagent/memory/MemoryService.java src/test/java/com/jobagent/memory/MemoryServiceTest.java
git commit -m "feat: 新增 MemoryService，编排记忆加载与抽取落库"
```

---

### Task 4: 记忆注入接线（CotPromptBuilder + AgentLoop + ChatController + 测试更新）

**Files:**
- Modify: `src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java`
- Modify: `src/main/java/com/jobagent/agent/AgentLoop.java`
- Modify: `src/main/java/com/jobagent/web/ChatController.java`（加 userId 参数透传）
- Test: `src/test/java/com/jobagent/agent/cot/CotPromptBuilderTest.java`（新建）
- Test: `src/test/java/com/jobagent/agent/AgentLoopTest.java`（改构造 + run 签名 + 新增注入测试）

**Interfaces:**
- Consumes: `MemoryService.load(String userId)`（Task 3）、`UserMemory`（Task 1）。
- Produces: `CotPromptBuilder.build(List<UserMemory> memories)`；`AgentLoop.run(String sessionId, String userId, String userInput, Consumer<AgentEvent> eventSink)`。

- [ ] **Step 1: 写 CotPromptBuilder 失败测试**

`CotPromptBuilderTest.java`：

```java
package com.jobagent.agent.cot;

import com.jobagent.memory.UserMemory;
import com.jobagent.tool.StudyPlanTool;
import com.jobagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CotPromptBuilderTest {

    @Test
    void buildWithoutMemoriesHasNoMemorySection() {
        CotPromptBuilder builder = new CotPromptBuilder(new ToolRegistry(List.of(new StudyPlanTool())));
        String prompt = builder.build(List.of());
        assertFalse(prompt.contains("长期记忆"));
    }

    @Test
    void buildWithMemoriesIncludesMemory() {
        CotPromptBuilder builder = new CotPromptBuilder(new ToolRegistry(List.of(new StudyPlanTool())));
        String prompt = builder.build(List.of(new UserMemory("name", "张三")));
        assertTrue(prompt.contains("张三"));
        assertTrue(prompt.contains("长期记忆"));
    }
}
```

Run: `mvn -q -Dtest=CotPromptBuilderTest test`
Expected: 编译失败（`build(List)` 不存在）

- [ ] **Step 2: 改 CotPromptBuilder**

`CotPromptBuilder.java` 全文替换为（加 `buildMemorySection` 私有方法 + `build` 加参数，新增 import `com.jobagent.memory.UserMemory`、`java.util.List`）：

```java
package com.jobagent.agent.cot;

import com.jobagent.memory.UserMemory;
import com.jobagent.tool.Tool;
import com.jobagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CotPromptBuilder {

    private final ToolRegistry toolRegistry;

    public String build(List<UserMemory> memories) {
        String tools = toolRegistry.getAll().stream()
                .map(t -> String.format("- %s: %s；参数：%s",
                        t.name(), t.description(), t.parametersSchema()))
                .collect(Collectors.joining("\n"));

        String memorySection = buildMemorySection(memories);

        return """
                你是一个求职规划智能 Agent，帮助软件工程应届生完成求职规划。
                %s
                你可以调用以下工具来完成任务：
                %s

                你必须只输出一个 JSON 对象（不要输出 markdown 代码块或其他文字），格式如下：
                1. 需要调用工具时：
                {"thinking": "你的思考过程", "tool": "工具名", "params": {参数}}
                2. 可以直接回答时：
                {"thinking": "你的思考过程", "final_answer": "最终答案"}

                要求：
                - thinking 字段写明你的推理过程。
                - 工具返回结果后，基于结果继续判断是否还需要调用其他工具，直到能给出最终答案。
                """.formatted(memorySection, tools);
    }

    private String buildMemorySection(List<UserMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        String lines = memories.stream()
                .map(m -> String.format("- [%s] %s", m.getType(), m.getContent()))
                .collect(Collectors.joining("\n"));
        return "你已知的关于用户的信息（长期记忆；若与用户最新说法冲突，以最新说法为准）：\n" + lines;
    }
}
```

- [ ] **Step 3: 运行 CotPromptBuilderTest 确认通过**

Run: `mvn -q -Dtest=CotPromptBuilderTest test`
Expected: 全部 PASS

- [ ] **Step 4: 改 AgentLoop**

`AgentLoop.java`：新增 import `com.jobagent.memory.MemoryService`、`com.jobagent.memory.UserMemory`；新增字段 `private final MemoryService memoryService;`；`run` 签名改为 `run(String sessionId, String userId, String userInput, Consumer<AgentEvent> eventSink)`；run 内部开头加载记忆并注入：

```java
public AgentResult run(String sessionId, String userId, String userInput, Consumer<AgentEvent> eventSink) {
    List<ChatMessage> history = sessionStore.getMessages(sessionId);
    List<UserMemory> memories = memoryService.load(userId);

    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage("system", promptBuilder.build(memories)));
    messages.addAll(history);
    messages.add(new ChatMessage("user", userInput));

    // ... 其余主循环逻辑完全不变 ...
}
```

（其余部分——while 循环、CoT 解析、工具调用、末尾 `sessionStore.append`——保持原样，只改上述三处。）

- [ ] **Step 5: 更新 AgentLoopTest**

`AgentLoopTest.java` 全文替换为（新增 `MemoryService` mock、`newLoop` 辅助、run 传 userId、新增注入测试）：

```java
package com.jobagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import com.jobagent.session.InMemorySessionStore;
import com.jobagent.session.SessionStore;
import com.jobagent.tool.InterviewQuestionTool;
import com.jobagent.tool.StudyPlanTool;
import com.jobagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private AgentLoop newLoop(LlmClient llm, MemoryService memoryService) {
        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool(), new InterviewQuestionTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();
        return new AgentLoop(llm, parser, promptBuilder, registry, sessionStore, memoryService);
    }

    @Test
    void runCallsToolThenFinishes() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("{\"thinking\":\"需要计划\",\"tool\":\"study_plan\",\"params\":{\"days\":30}}")
                .thenReturn("{\"thinking\":\"已生成\",\"final_answer\":\"计划完成\"}");
        MemoryService memoryService = mock(MemoryService.class);

        AgentResult result = newLoop(llm, memoryService).run("s1", "u1", "制定学习计划", null);

        assertEquals("计划完成", result.getFinalAnswer());
        assertEquals(1, result.getSteps().size());
        assertEquals("study_plan", result.getSteps().get(0).getTool());
        verify(llm, times(2)).chat(anyList());
    }

    @Test
    void unknownToolRetries() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("{\"thinking\":\"试试\",\"tool\":\"nope\",\"params\":{}}")
                .thenReturn("{\"thinking\":\"换一个\",\"final_answer\":\"完成\"}");
        MemoryService memoryService = mock(MemoryService.class);

        AgentResult result = newLoop(llm, memoryService).run("s2", "u1", "随便", null);

        assertEquals("完成", result.getFinalAnswer());
        assertEquals(0, result.getSteps().size());
    }

    @Test
    void parseFailureRetries() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("这不是合法JSON")
                .thenReturn("{\"thinking\":\"重试\",\"final_answer\":\"重试成功\"}");
        MemoryService memoryService = mock(MemoryService.class);

        AgentResult result = newLoop(llm, memoryService).run("s3", "u1", "测试", null);

        assertEquals("重试成功", result.getFinalAnswer());
        verify(llm, times(2)).chat(anyList());
    }

    @Test
    void injectsMemoryIntoSystemPrompt() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("{\"thinking\":\"ok\",\"final_answer\":\"你好\"}");
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(new UserMemory("name", "张三")));

        newLoop(llm, memoryService).run("s4", "u1", "你好", null);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue();
        assertEquals("system", sent.get(0).getRole());
        assertTrue(sent.get(0).getContent().contains("张三"));
        assertTrue(sent.get(0).getContent().contains("name"));
    }
}
```

- [ ] **Step 6: 改 ChatController 透传 userId**

`ChatController.java`：`stream` 方法加参数 `@RequestParam(defaultValue = "anonymous") String userId`，并把 `agentLoop.run(sessionId, message, ...)` 改为 `agentLoop.run(sessionId, userId, message, ...)`。（本 Task 暂不加 `memoryService` 字段与抽取调用，那是 Task 5。）

- [ ] **Step 7: 编译 + 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，所有测试 PASS

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/jobagent/agent/ src/main/java/com/jobagent/web/ChatController.java src/test/java/com/jobagent/agent/
git commit -m "feat: AgentLoop 注入长期记忆到系统提示，run 增加 userId 参数"
```

---

### Task 5: 记忆抽取接线 + 前端 userId

**Files:**
- Modify: `src/main/java/com/jobagent/web/ChatController.java`
- Modify: `src/main/resources/static/app.js`

**Interfaces:**
- Consumes: `MemoryService.extractAndStore(String userId, String sessionId, String userMessage, String assistantAnswer)`（Task 3）、`AgentLoop.run(..., userId, ...)`（Task 4）。

- [ ] **Step 1: ChatController 加 memoryService + 抽取调用**

`ChatController.java`：新增字段 `private final MemoryService memoryService;`，新增 import `com.jobagent.memory.MemoryService`。在 `stream` 方法里，发送 `final_answer` 事件之后、发送 `done` 事件之前插入：

```java
            emitter.send(SseEmitter.event().name("final_answer").data(result.getFinalAnswer()));
            memoryService.extractAndStore(userId, sessionId, message, result.getFinalAnswer());
            emitter.send(SseEmitter.event().name("done").data(""));
```

- [ ] **Step 2: app.js 加稳定 userId**

`app.js` 顶部（`sessionId` 之前）插入 userId 初始化，并把 `send()` 的 URL 加上 `&userId=`：

```javascript
let userId = localStorage.getItem('jobagent_user_id');
if (!userId) {
    userId = 'user-' + crypto.randomUUID();
    localStorage.setItem('jobagent_user_id', userId);
}

let sessionId = localStorage.getItem('jobagent_session_id');
if (!sessionId) {
    sessionId = 'session-' + crypto.randomUUID();
    localStorage.setItem('jobagent_session_id', sessionId);
}
```

`send()` 里 URL 改为：

```javascript
    const url = '/api/chat/stream?message=' + encodeURIComponent(msg) + '&sessionId=' + sessionId + '&userId=' + userId;
```

注意：`newSession()` 只重置 `sessionId`，**不动** `userId`（用户身份要跨会话稳定）。

- [ ] **Step 3: 编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/jobagent/web/ChatController.java src/main/resources/static/app.js
git commit -m "feat: 回答后抽取长期记忆落库，前端持久化稳定 userId"
```

---

## 端到端手动验证（执行者完成代码后）

1. 启动（Windows bash 环境变量前缀语法）：
   `MYSQL_PASSWORD=zhangkai1122 DEEPSEEK_API_KEY=<你的key> mvn spring-boot:run`
   启动时 Flyway 会自动执行 V2 迁移（日志应无报错）。
2. 打开 `http://localhost:8080`，发一条："我叫张三，目标岗位是 Java 后端"。
3. 查库确认抽取成功：
   ```sql
   SELECT u.username, m.memory_type, m.content FROM user_memory m
   JOIN app_user u ON u.id = m.user_id;
   ```
   应看到 `name=张三`、`target_role=Java后端`（或等价表述）。
4. 点"新会话"（清空页面，但 userId 不变），发："你还记得我叫什么、想找什么工作吗？" → Agent 应能说出"张三"和"Java 后端"。
5. 刷新页面 / 重启应用 → 再问同样问题 → 仍记得（验证记忆注入从库加载）。
6. 再发一句纠正："其实我改名了，叫李四" → 查库 `name` 应被覆盖为"李四"（验证 upsert 覆盖而非追加）。

---

## 自检记录（self-review）

- **Spec 覆盖**：身份识别（前端 userId）→ Task 5；记忆注入（AgentLoop/CotPromptBuilder）→ Task 4；记忆抽取（MemoryExtractor）→ Task 2；落库 upsert（UserMemoryStore + V2）→ Task 1；编排与尽力而为（MemoryService）→ Task 3。`chat_session.user_id` 不接线、`memory_type` 封闭集合、抽取同步、全量注入——均已体现在 Global Constraints 与对应实现中。
- **占位符扫描**：无 TBD/TODO，所有代码步骤含完整实现。
- **类型一致性**：`UserMemory` 字段 `type`/`content` 贯穿 store/extractor/service/prompt 各处一致；`MemoryService.load` 返回 `List<UserMemory>`、`extractAndStore` 返回 `void`；`AgentLoop.run` 六参签名与 ChatController/测试调用一致。
