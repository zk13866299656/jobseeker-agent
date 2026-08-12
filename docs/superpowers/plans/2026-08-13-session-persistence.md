# 切片2：会话持久化与记忆恢复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把会话历史从内存持久化到 MySQL，刷新/重启后仍能恢复对话。

**Architecture:** 新增 `MysqlSessionStore` 实现 `SessionStore` 接口（JdbcTemplate 手写 SQL），`AgentLoop` 主循环零改动；前端用 localStorage 持久化 `sessionId` 并在加载时拉取历史。

**Tech Stack:** Spring Boot 3.3.5 + Spring JDBC + Flyway + MySQL 9.2 + 原生 JS（无框架）

---

## 前置说明

- 数据库 `jobagent` 及 5 张表已手动建好（`V1__init.sql` 已存在并提交）。Flyway 用 baseline 模式跳过 V1。
- 密码用 `MYSQL_PASSWORD` 环境变量，不硬编码。
- `InMemorySessionStore` 保留（测试用），去掉其 `@Component`。

---

### Task 1: 添加 JDBC/Flyway 依赖与数据源配置

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 pom.xml 添加依赖**

在 `<dependencies>` 内、`spring-boot-starter-test` 之前插入：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
```

- [ ] **Step 2: 在 application.yml 添加数据源与 Flyway 配置**

将 `application.yml` 改为：

```yaml
server:
  port: 8080

spring:
  application:
    name: jobseeker-agent
  datasource:
    url: jdbc:mysql://localhost:3306/jobagent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1

llm:
  base-url: https://api.deepseek.com
  api-key: ${DEEPSEEK_API_KEY:}
  model: deepseek-chat
  temperature: 0.3
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS（依赖下载、编译通过）

- [ ] **Step 4: 提交**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "feat: 接入 JDBC/Flyway，配置 MySQL 数据源"
```

---

### Task 2: 实现 MysqlSessionStore（TDD）

**Files:**
- Create: `src/test/java/com/jobagent/session/MysqlSessionStoreTest.java`
- Create: `src/main/java/com/jobagent/session/MysqlSessionStore.java`
- Modify: `src/main/java/com/jobagent/session/InMemorySessionStore.java`（去掉 @Component）

- [ ] **Step 1: 写失败测试**

`MysqlSessionStoreTest.java`：

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlSessionStoreTest {

    @Test
    void getMessagesReturnsOrderedHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<ChatMessage> rows = List.of(
                new ChatMessage("user", "你好"),
                new ChatMessage("assistant", "你好，我是求职助手"));
        when(jdbc.query(
                eq("SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY id"),
                ArgumentMatchers.<RowMapper<ChatMessage>>any(),
                eq("s1")))
                .thenReturn(rows);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        List<ChatMessage> result = store.getMessages("s1");

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("你好", result.get(0).getContent());
    }

    @Test
    void appendUpsertsSessionThenInsertsMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MysqlSessionStore store = new MysqlSessionStore(jdbc);

        store.append("s1", new ChatMessage("user", "帮我制定学习计划"));

        verify(jdbc).update(
                eq("INSERT INTO chat_session (session_id) VALUES (?) ON DUPLICATE KEY UPDATE updated_at = NOW()"),
                eq("s1"));
        verify(jdbc).update(
                eq("INSERT INTO chat_message (session_id, role, content) VALUES (?, ?, ?)"),
                eq("s1"), eq("user"), eq("帮我制定学习计划"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MysqlSessionStoreTest test`
Expected: 编译失败（`MysqlSessionStore` 不存在）

- [ ] **Step 3: 实现 MysqlSessionStore**

`src/main/java/com/jobagent/session/MysqlSessionStore.java`：

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
            "SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY id";
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
}
```

- [ ] **Step 4: 去掉 InMemorySessionStore 的 @Component**

修改 `InMemorySessionStore.java`：删除 `@Component` 注解，并删除未使用的 import `org.springframework.stereotype.Component`。（保留类本身，测试仍在用）

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -Dtest=MysqlSessionStoreTest,InMemorySessionStoreTest,AgentLoopTest test`
Expected: 全部 PASS（若 Mockito 对 varargs 匹配报错，改用 `doReturn(rows).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<ChatMessage>>any(), any(Object[].class));` 形式）

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/jobagent/session/ src/test/java/com/jobagent/session/
git commit -m "feat: 新增 MysqlSessionStore，会话历史持久化到 MySQL"
```

---

### Task 3: 前端会话持久化 + 历史加载

**Files:**
- Modify: `src/main/java/com/jobagent/web/ChatController.java`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: ChatController 增加历史查询接口**

在 `ChatController` 增加字段 `private final SessionStore sessionStore;`，并新增方法：

```java
@GetMapping("/api/chat/history")
public List<ChatMessage> history(@RequestParam(defaultValue = "default") String sessionId) {
    return sessionStore.getMessages(sessionId);
}
```

补充 import：`com.jobagent.llm.ChatMessage`、`com.jobagent.session.SessionStore`、`java.util.List`。

- [ ] **Step 2: 重写 app.js（localStorage 持久化 + 加载历史 + 新会话）**

```javascript
let sessionId = localStorage.getItem('jobagent_session_id');
if (!sessionId) {
    sessionId = 'session-' + crypto.randomUUID();
    localStorage.setItem('jobagent_session_id', sessionId);
}

function addLine(cls, text) {
    const div = document.createElement('div');
    div.className = cls;
    div.textContent = text;
    document.getElementById('chat').appendChild(div);
}

function loadHistory() {
    fetch('/api/chat/history?sessionId=' + encodeURIComponent(sessionId))
        .then(res => res.json())
        .then(messages => {
            messages.forEach(m => {
                if (m.role === 'user') addLine('user', '我：' + m.content);
                else if (m.role === 'assistant') addLine('final_answer', '回答：' + m.content);
            });
        })
        .catch(() => {});
}

function send() {
    const input = document.getElementById('input');
    const msg = input.value.trim();
    if (!msg) return;
    input.value = '';
    addLine('user', '我：' + msg);

    const url = '/api/chat/stream?message=' + encodeURIComponent(msg) + '&sessionId=' + sessionId;
    const es = new EventSource(url);

    es.addEventListener('thinking', e => addLine('thinking', '思考：' + e.data));
    es.addEventListener('tool_call', e => addLine('tool_call', '调用工具：' + e.data));
    es.addEventListener('tool_result', e => addLine('tool_result', '工具结果：\n' + e.data));
    es.addEventListener('final_answer', e => addLine('final_answer', '回答：' + e.data));
    es.addEventListener('done', () => es.close());
    es.addEventListener('error', () => {
        addLine('error', '出错了，请稍后重试');
        es.close();
    });
}

function newSession() {
    sessionId = 'session-' + crypto.randomUUID();
    localStorage.setItem('jobagent_session_id', sessionId);
    document.getElementById('chat').innerHTML = '';
}

loadHistory();
```

- [ ] **Step 3: index.html 增加"新会话"按钮**

将发送按钮那一行改为：

```html
        <button onclick="send()">发送</button>
        <button onclick="newSession()">新会话</button>
```

- [ ] **Step 4: 编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/jobagent/web/ChatController.java src/main/resources/static/
git commit -m "feat: 前端持久化 sessionId 并加载历史，新增新会话按钮"
```

---

## 端到端手动验证（执行者完成代码后）

1. 启动：`MYSQL_PASSWORD=zhangkai1122 DEEPSEEK_API_KEY=<key> mvn spring-boot:run`
2. 打开 `http://localhost:8080`，发一条消息（如"我叫张三"）。
3. 刷新页面 → 应看到历史消息仍在，且后续回复记得"张三"。
4. 重启应用 → 刷新 → 历史仍恢复。
5. 点"新会话" → 清空，重新开始。
