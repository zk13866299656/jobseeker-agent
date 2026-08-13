# 上下文压缩（长对话摘要）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 长对话时，喂给 LLM 的历史从「全量」改为「滚动摘要 + 最近窗口原文」，token 封顶；数据库保留全部原文，前端仍可回看完整对话。

**Architecture:** 复用 `chat_message.msg_type`（normal / summary / archived 三态）标记消息；新增 `ContextCompressor` 组件在对话超阈值时调 LLM 生成滚动摘要；`AgentLoop` 加载历史时改用「摘要 + 未压缩原文」，末尾触发压缩。

**Tech Stack:** Java 17 + Spring Boot 3.3.5 + Maven + Lombok；JdbcTemplate 手写 SQL；JUnit 5 + Mockito（纯单测，不启动 Spring）；DeepSeek（deepseek-chat）。

**Spec:** `docs/superpowers/specs/2026-08-13-context-compression-design.md`

## Global Constraints

- Java 17，Spring Boot 3.3.5，Maven 构建，Lombok。
- 纯手搓，不引入 LangChain / Spring AI / tokenizer 等任何新依赖。
- 数据访问用 `JdbcTemplate` 手写 SQL，不用 JPA/MyBatis。
- 测试用 JUnit 5 + Mockito 纯单测（`new` 构造 + mock，不启动 Spring 上下文），测试类与主类同包。
- commit message 用中文，格式 `feat:` / `docs:` 等前缀（参考现有 git log）。
- 每个任务结束时整个项目必须 `mvn test` 编译并全部通过（Maven 整项目编译，一个类编译失败则全失败）。
- 摘要/压缩失败仅记日志（`log.warn`），绝不向调用方抛异常、不阻断回答。
- 常量默认值：触发阈值 `THRESHOLD_CHARS = 8000`，保留窗口 `KEEP_RECENT = 10`。

---

### Task 1: ChatMessage 增加 msgType 字段

**Files:**
- Modify: `src/main/java/com/jobagent/llm/ChatMessage.java`
- Test: `src/test/java/com/jobagent/llm/ChatMessageTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `ChatMessage` 新增 `String msgType`，默认 `"normal"`；保留 2 参构造器 `ChatMessage(String role, String content)`（msgType 默认 normal），新增 3 参构造器 `ChatMessage(String role, String content, String msgType)`。`@Data` 提供 `getMsgType()` / `setMsgType()`。后续所有任务依赖此字段区分 normal/summary/archived。

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jobagent/llm/ChatMessageTest.java`:

```java
package com.jobagent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTest {

    @Test
    void twoArgConstructorDefaultsToNormal() {
        ChatMessage m = new ChatMessage("user", "你好");
        assertEquals("user", m.getRole());
        assertEquals("你好", m.getContent());
        assertEquals("normal", m.getMsgType());
    }

    @Test
    void threeArgConstructorSetsType() {
        ChatMessage m = new ChatMessage("system", "摘要内容", "summary");
        assertEquals("summary", m.getMsgType());
    }

    @Test
    void setterChangesType() {
        ChatMessage m = new ChatMessage("user", "hi");
        m.setMsgType("archived");
        assertEquals("archived", m.getMsgType());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ChatMessageTest test`
Expected: FAIL — 编译错误，`ChatMessage` 没有 `getMsgType()` / 3 参构造器。

- [ ] **Step 3: Write minimal implementation**

Modify `src/main/java/com/jobagent/llm/ChatMessage.java`，去掉 `@AllArgsConstructor`，手动写两个构造器：

```java
package com.jobagent.llm;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChatMessage {
    private String role;    // system / user / assistant
    private String content;
    private String msgType = "normal";  // normal / summary / archived

    public ChatMessage(String role, String content) {
        this(role, content, "normal");
    }

    public ChatMessage(String role, String content, String msgType) {
        this.role = role;
        this.content = content;
        this.msgType = msgType;
    }
}
```

（去掉 `import lombok.AllArgsConstructor;`。保留 2 参构造器是为了不破坏现有 `new ChatMessage("user", "hi")` 的大量调用。）

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test`
Expected: PASS — 全量编译通过，所有现有测试仍绿（2 参构造器语义未变）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jobagent/llm/ChatMessage.java src/test/java/com/jobagent/llm/ChatMessageTest.java
git commit -m "feat: ChatMessage 新增 msgType 字段"
```

---

### Task 2: SessionStore 接口扩展 + 两个实现（InMemory / Mysql）

> 接口新增 5 个方法后，两个实现类必须同步实现，否则项目编译失败——故两个实现放同一任务。

**Files:**
- Modify: `src/main/java/com/jobagent/session/SessionStore.java`
- Modify: `src/main/java/com/jobagent/session/InMemorySessionStore.java`
- Modify: `src/main/java/com/jobagent/session/MysqlSessionStore.java`
- Test: `src/test/java/com/jobagent/session/InMemorySessionStoreTest.java`
- Test: `src/test/java/com/jobagent/session/MysqlSessionStoreTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ChatMessage.msgType`。
- Produces: `SessionStore` 接口新增以下方法，供 Task 3（ContextCompressor）、Task 5（AgentLoop）调用：
  - `List<ChatMessage> getNormalMessages(String sessionId)` — 返回 msg_type='normal' 的未压缩原文。
  - `String getLatestSummary(String sessionId)` — 返回最新一条摘要内容，无则 null。
  - `int countNormalChars(String sessionId)` — 统计 normal 消息 content 字符总数。
  - `void replaceSummary(String sessionId, String content)` — 删旧摘要、插新摘要（role='system'，msg_type='summary'）。
  - `void archiveOlder(String sessionId, int keepCount)` — 将 normal 中除最近 keepCount 条外标记为 archived。
  - `getMessages(String sessionId)` 改为过滤 summary（返回 normal + archived，供前端显示完整原文）。

- [ ] **Step 1: Write the failing tests**

Modify `src/test/java/com/jobagent/session/InMemorySessionStoreTest.java`，在现有类内追加：

```java
    @Test
    void getMessagesFiltersSummary() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "hi"));
        store.replaceSummary("s1", "摘要");
        store.append("s1", new ChatMessage("assistant", "hello"));
        List<ChatMessage> result = store.getMessages("s1");
        assertEquals(2, result.size());
        assertEquals("hi", result.get(0).getContent());
        assertEquals("hello", result.get(1).getContent());
    }

    @Test
    void getNormalMessagesFiltersSummaryAndArchived() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "m1"));
        store.append("s1", new ChatMessage("user", "m2"));
        store.append("s1", new ChatMessage("user", "m3"));
        store.archiveOlder("s1", 1);
        store.replaceSummary("s1", "摘要");
        List<ChatMessage> result = store.getNormalMessages("s1");
        assertEquals(1, result.size());
        assertEquals("m3", result.get(0).getContent());
    }

    @Test
    void getLatestSummaryReturnsNullWhenNone() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertNull(store.getLatestSummary("s1"));
    }

    @Test
    void getLatestSummaryReturnsLatest() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.replaceSummary("s1", "第一版");
        store.replaceSummary("s1", "第二版");
        assertEquals("第二版", store.getLatestSummary("s1"));
    }

    @Test
    void countNormalCharsSumsContentLength() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "你好"));
        store.append("s1", new ChatMessage("assistant", "hello world"));
        assertEquals(13, store.countNormalChars("s1"));
    }

    @Test
    void archiveOlderKeepsRecent() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "m1"));
        store.append("s1", new ChatMessage("user", "m2"));
        store.append("s1", new ChatMessage("user", "m3"));
        store.append("s1", new ChatMessage("user", "m4"));
        store.archiveOlder("s1", 2);
        List<ChatMessage> normals = store.getNormalMessages("s1");
        assertEquals(2, normals.size());
        assertEquals("m3", normals.get(0).getContent());
        assertEquals("m4", normals.get(1).getContent());
    }
```

（`InMemorySessionStoreTest.java` 需补 `import java.util.List;`。）

Modify `src/test/java/com/jobagent/session/MysqlSessionStoreTest.java`：

- 先更新现有 `getMessagesReturnsOrderedHistory` 的 SQL 断言（改成带 `AND msg_type <> 'summary'`），并在类内追加：

```java
    @Test
    void getMessagesFiltersSummary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<ChatMessage> rows = List.of(new ChatMessage("user", "你好"));
        when(jdbc.query(
                eq("SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type <> 'summary' ORDER BY id"),
                ArgumentMatchers.<RowMapper<ChatMessage>>any(),
                eq("s1")))
                .thenReturn(rows);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals(1, store.getMessages("s1").size());
    }

    @Test
    void getNormalMessagesFiltersNormalOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<ChatMessage> rows = List.of(new ChatMessage("user", "hi"));
        when(jdbc.query(
                eq("SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id"),
                ArgumentMatchers.<RowMapper<ChatMessage>>any(),
                eq("s1")))
                .thenReturn(rows);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals(1, store.getNormalMessages("s1").size());
    }

    @Test
    void getLatestSummaryReturnsContentOrNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1"),
                ArgumentMatchers.<RowMapper<String>>any(),
                eq("s1")))
                .thenReturn(List.of("摘要内容"));
        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals("摘要内容", store.getLatestSummary("s1"));

        when(jdbc.query(
                eq("SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1"),
                ArgumentMatchers.<RowMapper<String>>any(),
                eq("s2")))
                .thenReturn(List.of());
        assertNull(store.getLatestSummary("s2"));
    }

    @Test
    void countNormalCharsQueriesSum() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                eq("SELECT COALESCE(SUM(CHAR_LENGTH(content)), 0) FROM chat_message WHERE session_id = ? AND msg_type = 'normal'"),
                eq(Long.class), eq("s1")))
                .thenReturn(5000L);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals(5000, store.countNormalChars("s1"));
    }

    @Test
    void replaceSummaryDeletesThenInserts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MysqlSessionStore store = new MysqlSessionStore(jdbc);

        store.replaceSummary("s1", "新摘要");

        verify(jdbc).update(
                eq("DELETE FROM chat_message WHERE session_id = ? AND msg_type = 'summary'"),
                eq("s1"));
        verify(jdbc).update(
                eq("INSERT INTO chat_message (session_id, role, content, msg_type) VALUES (?, 'system', ?, 'summary')"),
                eq("s1"), eq("新摘要"));
    }

    @Test
    void archiveOlderKeepsRecentById() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT id FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id"),
                ArgumentMatchers.<RowMapper<Long>>any(),
                eq("s1")))
                .thenReturn(List.of(1L, 2L, 3L, 4L));

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        store.archiveOlder("s1", 2);

        verify(jdbc).update(
                eq("UPDATE chat_message SET msg_type = 'archived' WHERE session_id = ? AND msg_type = 'normal' AND id < ?"),
                eq("s1"), eq(3L));
    }

    @Test
    void archiveOlderNoOpWhenFewerThanKeep() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT id FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id"),
                ArgumentMatchers.<RowMapper<Long>>any(),
                eq("s1")))
                .thenReturn(List.of(1L, 2L));

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        store.archiveOlder("s1", 5);

        verify(jdbc, never()).update(anyString(), any(), any());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=InMemorySessionStoreTest,MysqlSessionStoreTest test`
Expected: FAIL — 编译错误，`SessionStore` 接口无这些新方法 / `MysqlSessionStore`、`InMemorySessionStore` 未实现。

- [ ] **Step 3: Write minimal implementation**

Modify `src/main/java/com/jobagent/session/SessionStore.java`：

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;

import java.util.List;

public interface SessionStore {
    List<ChatMessage> getMessages(String sessionId);
    void append(String sessionId, ChatMessage message);

    List<ChatMessage> getNormalMessages(String sessionId);
    String getLatestSummary(String sessionId);
    int countNormalChars(String sessionId);
    void replaceSummary(String sessionId, String content);
    void archiveOlder(String sessionId, int keepCount);
}
```

Modify `src/main/java/com/jobagent/session/InMemorySessionStore.java`：

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionStore implements SessionStore {

    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(String sessionId) {
        return store.getOrDefault(sessionId, new ArrayList<>()).stream()
                .filter(m -> !"summary".equals(m.getMsgType()))
                .toList();
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public List<ChatMessage> getNormalMessages(String sessionId) {
        return store.getOrDefault(sessionId, new ArrayList<>()).stream()
                .filter(m -> "normal".equals(m.getMsgType()))
                .toList();
    }

    @Override
    public String getLatestSummary(String sessionId) {
        List<ChatMessage> all = store.getOrDefault(sessionId, new ArrayList<>());
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("summary".equals(all.get(i).getMsgType())) {
                return all.get(i).getContent();
            }
        }
        return null;
    }

    @Override
    public int countNormalChars(String sessionId) {
        return getNormalMessages(sessionId).stream()
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                .sum();
    }

    @Override
    public void replaceSummary(String sessionId, String content) {
        List<ChatMessage> all = store.computeIfAbsent(sessionId, k -> new ArrayList<>());
        all.removeIf(m -> "summary".equals(m.getMsgType()));
        all.add(new ChatMessage("system", content, "summary"));
    }

    @Override
    public void archiveOlder(String sessionId, int keepCount) {
        List<ChatMessage> normals = getNormalMessages(sessionId);
        for (int i = 0; i < normals.size() - keepCount; i++) {
            normals.get(i).setMsgType("archived");
        }
    }
}
```

Modify `src/main/java/com/jobagent/session/MysqlSessionStore.java`，完整替换为：

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MysqlSessionStore implements SessionStore {

    private static final String SELECT_MESSAGES =
            "SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type <> 'summary' ORDER BY id";
    private static final String SELECT_NORMAL =
            "SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id";
    private static final String SELECT_LATEST_SUMMARY =
            "SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1";
    private static final String COUNT_NORMAL_CHARS =
            "SELECT COALESCE(SUM(CHAR_LENGTH(content)), 0) FROM chat_message WHERE session_id = ? AND msg_type = 'normal'";
    private static final String DELETE_SUMMARY =
            "DELETE FROM chat_message WHERE session_id = ? AND msg_type = 'summary'";
    private static final String INSERT_SUMMARY =
            "INSERT INTO chat_message (session_id, role, content, msg_type) VALUES (?, 'system', ?, 'summary')";
    private static final String SELECT_NORMAL_IDS =
            "SELECT id FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id";
    private static final String ARCHIVE_OLDER =
            "UPDATE chat_message SET msg_type = 'archived' WHERE session_id = ? AND msg_type = 'normal' AND id < ?";
    private static final String UPSERT_SESSION =
            "INSERT INTO chat_session (session_id) VALUES (?) ON DUPLICATE KEY UPDATE updated_at = NOW()";
    private static final String INSERT_MESSAGE =
            "INSERT INTO chat_message (session_id, role, content) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ChatMessage> getMessages(String sessionId) {
        return jdbcTemplate.query(SELECT_MESSAGES,
                (rs, rowNum) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                sessionId);
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        jdbcTemplate.update(UPSERT_SESSION, sessionId);
        jdbcTemplate.update(INSERT_MESSAGE, sessionId, message.getRole(), message.getContent());
    }

    @Override
    public List<ChatMessage> getNormalMessages(String sessionId) {
        return jdbcTemplate.query(SELECT_NORMAL,
                (rs, rowNum) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                sessionId);
    }

    @Override
    public String getLatestSummary(String sessionId) {
        List<String> result = jdbcTemplate.query(SELECT_LATEST_SUMMARY,
                (rs, rowNum) -> rs.getString("content"), sessionId);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public int countNormalChars(String sessionId) {
        Long n = jdbcTemplate.queryForObject(COUNT_NORMAL_CHARS, Long.class, sessionId);
        return n == null ? 0 : n.intValue();
    }

    @Override
    public void replaceSummary(String sessionId, String content) {
        jdbcTemplate.update(DELETE_SUMMARY, sessionId);
        jdbcTemplate.update(INSERT_SUMMARY, sessionId, content);
    }

    @Override
    public void archiveOlder(String sessionId, int keepCount) {
        List<Long> ids = jdbcTemplate.query(SELECT_NORMAL_IDS,
                (rs, rowNum) -> rs.getLong("id"), sessionId);
        if (ids.size() <= keepCount) {
            return;
        }
        long keepFromId = ids.get(ids.size() - keepCount);
        jdbcTemplate.update(ARCHIVE_OLDER, sessionId, keepFromId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test`
Expected: PASS — 全量编译通过，所有测试绿（含旧的 `appendUpsertsSessionThenInsertsMessage` 不变）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jobagent/session/SessionStore.java src/main/java/com/jobagent/session/InMemorySessionStore.java src/main/java/com/jobagent/session/MysqlSessionStore.java src/test/java/com/jobagent/session/InMemorySessionStoreTest.java src/test/java/com/jobagent/session/MysqlSessionStoreTest.java
git commit -m "feat: SessionStore 扩展上下文压缩相关方法"
```

---

### Task 3: 新增 ContextCompressor 组件

**Files:**
- Create: `src/main/java/com/jobagent/agent/ContextCompressor.java`
- Test: `src/test/java/com/jobagent/agent/ContextCompressorTest.java`

**Interfaces:**
- Consumes: Task 2 的 `SessionStore`（`countNormalChars` / `getLatestSummary` / `getNormalMessages` / `replaceSummary` / `archiveOlder`）；`LlmClient.chat(List<ChatMessage>)`（已有）。
- Produces: `ContextCompressor.compressIfNeeded(String sessionId)`（无返回值，失败静默）；package-private `List<ChatMessage> buildSummaryMessages(String oldSummary, List<ChatMessage> normals)` 供测试；package-private 常量 `THRESHOLD_CHARS = 8000`、`KEEP_RECENT = 10`。Task 5 调用 `compressIfNeeded`。

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/jobagent/agent/ContextCompressorTest.java`:

```java
package com.jobagent.agent;

import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ContextCompressorTest {

    @Test
    void belowThresholdDoesNothing() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(8000);

        new ContextCompressor(llm, store).compressIfNeeded("s1");

        verify(llm, never()).chat(anyList());
        verify(store, never()).replaceSummary(anyString(), anyString());
        verify(store, never()).archiveOlder(anyString(), anyInt());
    }

    @Test
    void aboveThresholdSummarizesAndArchives() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(8001);
        when(store.getLatestSummary("s1")).thenReturn("旧摘要");
        when(store.getNormalMessages("s1")).thenReturn(List.of(
                new ChatMessage("user", "你好"),
                new ChatMessage("assistant", "你好，我是求职助手")));
        when(llm.chat(anyList())).thenReturn("新摘要内容");

        new ContextCompressor(llm, store).compressIfNeeded("s1");

        verify(store).replaceSummary("s1", "新摘要内容");
        verify(store).archiveOlder("s1", ContextCompressor.KEEP_RECENT);
    }

    @Test
    void llmFailureIsSwallowed() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(9000);
        when(llm.chat(anyList())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> new ContextCompressor(llm, store).compressIfNeeded("s1"));

        verify(store, never()).replaceSummary(anyString(), anyString());
        verify(store, never()).archiveOlder(anyString(), anyInt());
    }

    @Test
    void blankSummaryDoesNotStore() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(9000);
        when(llm.chat(anyList())).thenReturn("   ");

        new ContextCompressor(llm, store).compressIfNeeded("s1");

        verify(store, never()).replaceSummary(anyString(), anyString());
        verify(store, never()).archiveOlder(anyString(), anyInt());
    }

    @Test
    void buildSummaryMessagesIncludesOldSummaryAndDialog() {
        ContextCompressor c = new ContextCompressor(mock(LlmClient.class), mock(SessionStore.class));
        List<ChatMessage> msgs = c.buildSummaryMessages("旧摘要", List.of(
                new ChatMessage("user", "我想找后端")));

        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).getRole());
        assertEquals("user", msgs.get(1).getRole());
        assertTrue(msgs.get(1).getContent().contains("旧摘要"));
        assertTrue(msgs.get(1).getContent().contains("我想找后端"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ContextCompressorTest test`
Expected: FAIL — 编译错误，`ContextCompressor` 类不存在。

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/jobagent/agent/ContextCompressor.java`:

```java
package com.jobagent.agent;

import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.SessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    static final int THRESHOLD_CHARS = 8000;
    static final int KEEP_RECENT = 10;

    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是一个对话摘要助手。请把下面的求职规划对话浓缩成一段简洁的中文摘要（不超过300字）。" +
            "只保留：用户的关键事实（称呼、目标岗位、技能、求职进度）、用户偏好、待办事项。" +
            "不要臆造信息，不要添加对话中不存在的内容。";

    private final LlmClient llmClient;
    private final SessionStore sessionStore;

    public void compressIfNeeded(String sessionId) {
        try {
            int chars = sessionStore.countNormalChars(sessionId);
            if (chars <= THRESHOLD_CHARS) {
                return;
            }
            String oldSummary = sessionStore.getLatestSummary(sessionId);
            List<ChatMessage> normals = sessionStore.getNormalMessages(sessionId);
            String newSummary = llmClient.chat(buildSummaryMessages(oldSummary, normals));
            if (newSummary == null || newSummary.isBlank()) {
                return;
            }
            sessionStore.replaceSummary(sessionId, newSummary.trim());
            sessionStore.archiveOlder(sessionId, KEEP_RECENT);
        } catch (Exception e) {
            log.warn("上下文压缩失败（不影响回答）", e);
        }
    }

    List<ChatMessage> buildSummaryMessages(String oldSummary, List<ChatMessage> normals) {
        StringBuilder sb = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("之前的摘要：\n").append(oldSummary).append("\n\n");
        }
        sb.append("最新对话：\n");
        for (ChatMessage m : normals) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        return List.of(
                new ChatMessage("system", SUMMARY_SYSTEM_PROMPT),
                new ChatMessage("user", sb.toString())
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test`
Expected: PASS — 全量编译通过，所有测试绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jobagent/agent/ContextCompressor.java src/test/java/com/jobagent/agent/ContextCompressorTest.java
git commit -m "feat: 新增 ContextCompressor 上下文压缩组件"
```

---

### Task 4: CotPromptBuilder 支持注入对话摘要

**Files:**
- Modify: `src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java`
- Test: `src/test/java/com/jobagent/agent/cot/CotPromptBuilderTest.java`

**Interfaces:**
- Consumes: `ToolRegistry`（已有）、`UserMemory`（已有）。
- Produces: 新增 `String build(List<UserMemory> memories, String summary)`；保留单参 `String build(List<UserMemory> memories)` 委托到 `build(memories, null)`（本任务过渡用，Task 5 会删除）。summary 非空时在 system prompt 中追加「之前对话的摘要」段。

- [ ] **Step 1: Write the failing test**

Modify `src/test/java/com/jobagent/agent/cot/CotPromptBuilderTest.java`，改为（并追加摘要相关用例）：

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
        String prompt = builder.build(List.of(), null);
        assertFalse(prompt.contains("长期记忆"));
    }

    @Test
    void buildWithMemoriesIncludesMemory() {
        CotPromptBuilder builder = new CotPromptBuilder(new ToolRegistry(List.of(new StudyPlanTool())));
        String prompt = builder.build(List.of(new UserMemory("name", "张三")), null);
        assertTrue(prompt.contains("张三"));
        assertTrue(prompt.contains("长期记忆"));
    }

    @Test
    void buildWithSummaryIncludesSummarySection() {
        CotPromptBuilder builder = new CotPromptBuilder(new ToolRegistry(List.of(new StudyPlanTool())));
        String prompt = builder.build(List.of(), "这是一段之前的对话摘要");
        assertTrue(prompt.contains("这是一段之前的对话摘要"));
        assertTrue(prompt.contains("之前对话的摘要"));
    }

    @Test
    void buildWithNullSummaryHasNoSummarySection() {
        CotPromptBuilder builder = new CotPromptBuilder(new ToolRegistry(List.of(new StudyPlanTool())));
        String prompt = builder.build(List.of(), null);
        assertFalse(prompt.contains("之前对话的摘要"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=CotPromptBuilderTest test`
Expected: FAIL — 编译错误，`build(List, String)` 不存在。

- [ ] **Step 3: Write minimal implementation**

Modify `src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java`：

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
        return build(memories, null);
    }

    public String build(List<UserMemory> memories, String summary) {
        String tools = toolRegistry.getAll().stream()
                .map(t -> String.format("- %s: %s；参数：%s",
                        t.name(), t.description(), t.parametersSchema()))
                .collect(Collectors.joining("\n"));

        String memorySection = buildMemorySection(memories);
        String summarySection = buildSummarySection(summary);

        return """
                你是一个求职规划智能 Agent，帮助软件工程应届生完成求职规划。
                %s
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
                """.formatted(memorySection, summarySection, tools);
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

    private String buildSummarySection(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        return "以下是之前对话的摘要（若与用户最新说法冲突，以最新说法为准）：\n" + summary;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test`
Expected: PASS — 全量编译通过（单参 `build` 仍保留，AgentLoop 未改仍可编译）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java src/test/java/com/jobagent/agent/cot/CotPromptBuilderTest.java
git commit -m "feat: CotPromptBuilder 支持注入对话摘要"
```

---

### Task 5: AgentLoop 接入上下文压缩

**Files:**
- Modify: `src/main/java/com/jobagent/agent/AgentLoop.java`
- Modify: `src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java`（删除单参 `build` 过渡方法）
- Test: `src/test/java/com/jobagent/agent/AgentLoopTest.java`
- Test: `src/test/java/com/jobagent/agent/cot/CotPromptBuilderTest.java`（单参重载删除后无需改，已全用双参）

**Interfaces:**
- Consumes: Task 2 的 `SessionStore` 新方法（`getNormalMessages` / `getLatestSummary`）；Task 3 的 `ContextCompressor.compressIfNeeded`；Task 4 的 `CotPromptBuilder.build(memories, summary)`。
- Produces: `AgentLoop.run` 加载历史改为「摘要 + 未压缩原文」，末尾（append 后）触发压缩。构造函数新增 `ContextCompressor` 参数（`@RequiredArgsConstructor` 按字段顺序注入）。

- [ ] **Step 1: Write the failing test**

Modify `src/test/java/com/jobagent/agent/AgentLoopTest.java`：

- `newLoop` helper 增加 `ContextCompressor` 参数，并追加两个测试：

```java
    private AgentLoop newLoop(LlmClient llm, MemoryService memoryService, ContextCompressor compressor) {
        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool(), new InterviewQuestionTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();
        return new AgentLoop(llm, parser, promptBuilder, registry, sessionStore, memoryService, compressor);
    }
```

现有 4 个测试的 `newLoop(llm, memoryService)` 调用，统一改成 `newLoop(llm, memoryService, mock(ContextCompressor.class))`。

追加两个测试：

```java
    @Test
    void runSendsSummaryAndNormalButNotArchived() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("{\"thinking\":\"ok\",\"final_answer\":\"你好\"}");
        MemoryService memoryService = mock(MemoryService.class);

        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s5", new ChatMessage("user", "旧的已归档消息"));
        store.archiveOlder("s5", 0);
        store.replaceSummary("s5", "这是摘要");
        store.append("s5", new ChatMessage("user", "最新消息"));

        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        AgentLoop loop = new AgentLoop(llm, parser, promptBuilder, registry, store, memoryService, mock(ContextCompressor.class));

        loop.run("s5", "u1", "你好", null);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue();
        assertTrue(sent.get(0).getContent().contains("这是摘要"));
        assertTrue(sent.stream().anyMatch(m -> "最新消息".equals(m.getContent())));
        assertTrue(sent.stream().noneMatch(m -> "旧的已归档消息".equals(m.getContent())));
    }

    @Test
    void runCallsCompressAfterAppend() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("{\"thinking\":\"ok\",\"final_answer\":\"你好\"}");
        MemoryService memoryService = mock(MemoryService.class);
        ContextCompressor compressor = mock(ContextCompressor.class);

        newLoop(llm, memoryService, compressor).run("s6", "u1", "你好", null);

        verify(compressor).compressIfNeeded("s6");
    }
```

（`AgentLoopTest.java` 已 `import com.jobagent.session.InMemorySessionStore;`、`import com.jobagent.llm.ChatMessage;`。）

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=AgentLoopTest test`
Expected: FAIL — 编译错误，`AgentLoop` 构造器缺 `ContextCompressor` 参数 / 加载历史未用新方法（`runSendsSummary...` 断言失败）。

- [ ] **Step 3: Write minimal implementation**

Modify `src/main/java/com/jobagent/agent/AgentLoop.java`：

```java
package com.jobagent.agent;

import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.agent.cot.CotResult;
import com.jobagent.common.BizException;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import com.jobagent.session.SessionStore;
import com.jobagent.tool.Tool;
import com.jobagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final int MAX_STEPS = 10;

    private final LlmClient llmClient;
    private final CotParser cotParser;
    private final CotPromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final SessionStore sessionStore;
    private final MemoryService memoryService;
    private final ContextCompressor contextCompressor;

    public AgentResult run(String sessionId, String userId, String userInput, Consumer<AgentEvent> eventSink) {
        List<ChatMessage> history = sessionStore.getNormalMessages(sessionId);
        String summary = sessionStore.getLatestSummary(sessionId);
        List<UserMemory> memories = memoryService.load(userId);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", promptBuilder.build(memories, summary)));
        messages.addAll(history);
        messages.add(new ChatMessage("user", userInput));

        List<AgentStep> steps = new ArrayList<>();
        String finalAnswer = null;

        int stepNo = 0;
        while (stepNo < MAX_STEPS) {
            stepNo++;

            String raw = llmClient.chat(messages);
            messages.add(new ChatMessage("assistant", raw));

            CotResult cot;
            try {
                cot = cotParser.parse(raw);
            } catch (BizException e) {
                log.warn("CoT 解析失败: {}", e.getMessage());
                messages.add(new ChatMessage("user", "你的输出不是合法 JSON，请只输出一个 JSON 对象（含 thinking 字段，以及 tool+params 或 final_answer 之一）"));
                continue;
            }
            emit(eventSink, "thinking", cot.getThinking());

            if (cot.getType() == CotResult.Type.FINAL_ANSWER) {
                finalAnswer = cot.getFinalAnswer();
                break;
            }

            Tool tool = toolRegistry.find(cot.getTool());
            if (tool == null) {
                messages.add(new ChatMessage("user", "未知工具: " + cot.getTool() + "，请重新选择可用工具"));
                continue;
            }

            emit(eventSink, "tool_call", tool.name());
            String toolResult;
            try {
                toolResult = tool.execute(cot.getParams());
            } catch (Exception e) {
                log.warn("工具执行异常: {}", e.getMessage());
                toolResult = "工具执行失败：" + e.getMessage();
            }
            emit(eventSink, "tool_result", toolResult);

            AgentStep step = new AgentStep();
            step.setIndex(stepNo);
            step.setThinking(cot.getThinking());
            step.setTool(tool.name());
            step.setParams(String.valueOf(cot.getParams()));
            step.setResult(toolResult);
            steps.add(step);

            messages.add(new ChatMessage("user", "工具 " + tool.name() + " 执行结果：\n" + toolResult));
        }

        if (finalAnswer == null) {
            finalAnswer = "已达到最大执行步数，未能完成。已执行步骤见日志。";
        }

        sessionStore.append(sessionId, new ChatMessage("user", userInput));
        sessionStore.append(sessionId, new ChatMessage("assistant", finalAnswer));
        contextCompressor.compressIfNeeded(sessionId);

        AgentResult result = new AgentResult();
        result.setFinalAnswer(finalAnswer);
        result.setSteps(steps);
        return result;
    }

    private void emit(Consumer<AgentEvent> sink, String type, String content) {
        if (sink != null) {
            sink.accept(new AgentEvent(type, content));
        }
    }
}
```

删除 `src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java` 中的单参过渡方法：

```java
    public String build(List<UserMemory> memories) {
        return build(memories, null);
    }
```

（移除该方法，只保留双参 `build`。Task 4 测试已全部改用双参，无影响。）

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test`
Expected: PASS — 全量编译通过，所有测试绿。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/jobagent/agent/AgentLoop.java src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java src/test/java/com/jobagent/agent/AgentLoopTest.java
git commit -m "feat: AgentLoop 接入上下文压缩"
```

---

## 端到端手动验证（写完代码后）

启动：`MYSQL_PASSWORD=zhangkai1122 DEEPSEEK_API_KEY=<key> mvn spring-boot:run`，浏览器开 `http://localhost:8080`。

1. **正常短对话不受影响**：聊几句，`/api/chat/history` 应仍返回全部原文。
2. **长对话触发压缩**：连续发长消息（累计超 8000 字符），观察日志出现「上下文压缩」相关调用；刷新后前端仍能看到完整历史（含早期原文）。
3. **压缩后模型仍记得早期内容**：压缩触发后，新会话问一个早期对话里提过的事，模型应能基于摘要回答（稳定事实另由长期记忆兜底）。

## Self-Review 记录

- **Spec 覆盖**：三态标记 → Task 1/2；滚动摘要 → Task 3；token(字符数)触发 → Task 3 常量；保留窗口 → Task 3 `KEEP_RECENT`；摘要注入 system prompt → Task 4；原文保留 + 前端过滤 summary → Task 2 `getMessages`；AgentLoop 接线 → Task 5；尽力而为 → Task 3 try/catch。全部覆盖，无缺口。
- **类型一致性**：`build(memories, summary)`、`getNormalMessages`、`getLatestSummary`、`countNormalChars`、`replaceSummary`、`archiveOlder`、`compressIfNeeded` 在 Task 2/3/4/5 间签名一致。
- **占位符**：无 TBD/TODO，所有代码步骤含完整代码块。
