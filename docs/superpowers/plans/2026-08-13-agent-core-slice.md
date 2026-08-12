# 切片1：Agent 核心引擎最小垂直切片 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 端到端跑通自研 Agent 主循环，实现「自然语言指令 → CoT 思考 → 调工具 → 出结果」的闭环，并提供一个极简 Web 对话页流式展示全过程。

**Architecture:** 分层清晰的小模块：LLM 适配层（可配置切换）→ CoT 解析器（自研结构化 JSON 解析）→ 自研主循环（while 驱动）→ 插件式工具（统一 Tool 接口 + 注册表）→ 内存会话 → SSE 控制器 + 静态前端。所有核心逻辑纯 Java 自研，工具通过 Spring 自动注入注册，新增工具零改动核心调度代码。

**Tech Stack:** SpringBoot 3.3.5 + Java 17 + Maven + Lombok + RestTemplate（调 OpenAI 兼容接口）+ SseEmitter（SSE 流式）+ 原生 JS（前端）。

**约定：**
- 包名 `com.jobagent`，主类 `JobAgentApplication`。
- commit message 一律中文，每完成一个 Task 提交一次。
- 除 Task 1/3/8 外，每个 Task 遵循 TDD：先写失败测试 → 跑红 → 实现 → 跑绿 → 提交。
- 测试均为纯 JUnit5 + Mockito（不加载 Spring 上下文），单测快速。

---

## 文件结构总览

```
pom.xml
src/main/java/com/jobagent/
├── JobAgentApplication.java        # 启动类，启用 LlmProperties
├── common/
│   ├── Result.java                 # 统一返回体
│   ├── BizException.java           # 业务异常
│   └── GlobalExceptionHandler.java # 全局异常处理
├── config/
│   ├── LlmProperties.java          # LLM 配置（base-url/api-key/model/temperature）
│   └── AppConfig.java              # RestTemplate + Executor Bean
├── llm/
│   ├── ChatMessage.java            # 消息（role + content）
│   ├── LlmClient.java              # LLM 调用接口
│   └── DeepSeekClient.java         # OpenAI 兼容 HTTP 实现（含重试）
├── agent/
│   ├── AgentLoop.java              # 自研主循环
│   ├── AgentStep.java              # 单步执行记录
│   ├── AgentResult.java            # 循环返回（最终答案 + 步骤日志）
│   ├── AgentEvent.java             # 流式事件
│   └── cot/
│       ├── CotResult.java          # CoT 解析产物（工具调用 / 最终答案）
│       ├── CotParser.java          # CoT JSON 解析器
│       └── CotPromptBuilder.java   # 系统提示词构建（注入工具列表）
├── tool/
│   ├── Tool.java                   # 工具接口
│   ├── ToolRegistry.java           # 工具注册表
│   ├── StudyPlanTool.java          # 学习计划生成工具
│   └── InterviewQuestionTool.java  # 面试题检索工具
├── session/
│   ├── SessionStore.java           # 会话存储接口
│   └── InMemorySessionStore.java   # 内存实现
└── web/
    └── ChatController.java         # SSE 控制器
src/main/resources/
├── application.yml
└── static/
    ├── index.html
    └── app.js
src/test/java/com/jobagent/
├── common/ResultTest.java
├── agent/cot/CotParserTest.java
├── tool/ToolRegistryTest.java
├── tool/StudyPlanToolTest.java
├── tool/InterviewQuestionToolTest.java
├── session/InMemorySessionStoreTest.java
└── agent/AgentLoopTest.java
```

---

## Task 1: 工程骨架（可编译）

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/jobagent/JobAgentApplication.java`
- Create: `src/main/java/com/jobagent/config/LlmProperties.java`
- Create: `src/main/java/com/jobagent/config/AppConfig.java`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.jobagent</groupId>
    <artifactId>jobseeker-agent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>jobseeker-agent</name>
    <description>求职规划智能 Agent（切片1：Agent 核心引擎）</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建启动类**

`src/main/java/com/jobagent/JobAgentApplication.java`

```java
package com.jobagent;

import com.jobagent.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class JobAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobAgentApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 LlmProperties**

`src/main/java/com/jobagent/config/LlmProperties.java`

```java
package com.jobagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey;
    private String model = "deepseek-chat";
    private double temperature = 0.3;
}
```

- [ ] **Step 4: 创建 AppConfig（RestTemplate + Executor）**

`src/main/java/com/jobagent/config/AppConfig.java`

```java
package com.jobagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Executor agentExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
```

- [ ] **Step 5: 创建 application.yml**

`src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: jobseeker-agent

llm:
  base-url: https://api.deepseek.com
  api-key: ${DEEPSEEK_API_KEY:}
  model: deepseek-chat
  temperature: 0.3
```

- [ ] **Step 6: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（无报错）

- [ ] **Step 7: 提交**

```bash
git add pom.xml src/
git commit -m "feat: 初始化 SpringBoot 工程骨架与配置"
git push
```

---

## Task 2: 统一返回体与全局异常

**Files:**
- Create: `src/main/java/com/jobagent/common/Result.java`
- Create: `src/main/java/com/jobagent/common/BizException.java`
- Create: `src/main/java/com/jobagent/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/jobagent/common/ResultTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/jobagent/common/ResultTest.java`

```java
package com.jobagent.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void ok() {
        Result<String> r = Result.ok("data");
        assertEquals(0, r.getCode());
        assertEquals("success", r.getMessage());
        assertEquals("data", r.getData());
    }

    @Test
    void error() {
        Result<Void> r = Result.error(500, "失败");
        assertEquals(500, r.getCode());
        assertEquals("失败", r.getMessage());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ResultTest test`
Expected: 编译失败（`Result` 类不存在）

- [ ] **Step 3: 实现 Result / BizException / GlobalExceptionHandler**

`src/main/java/com/jobagent/common/Result.java`

```java
package com.jobagent.common;

import lombok.Data;

@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
```

`src/main/java/com/jobagent/common/BizException.java`

```java
package com.jobagent.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

`src/main/java/com/jobagent/common/GlobalExceptionHandler.java`

```java
package com.jobagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统异常，请稍后重试");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ResultTest test`
Expected: BUILD SUCCESS，2 个测试通过

- [ ] **Step 5: 提交**

```bash
git add src/
git commit -m "feat: 添加统一返回体与全局异常处理"
git push
```

---

## Task 3: LLM 适配层

**Files:**
- Create: `src/main/java/com/jobagent/llm/ChatMessage.java`
- Create: `src/main/java/com/jobagent/llm/LlmClient.java`
- Create: `src/main/java/com/jobagent/llm/DeepSeekClient.java`

（本 Task 为 HTTP 胶水代码，用 Task 7 的 mock 测试覆盖；此处只做编译验证。）

- [ ] **Step 1: 创建 ChatMessage**

`src/main/java/com/jobagent/llm/ChatMessage.java`

```java
package com.jobagent.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String role;    // system / user / assistant
    private String content;
}
```

- [ ] **Step 2: 创建 LlmClient 接口**

`src/main/java/com/jobagent/llm/LlmClient.java`

```java
package com.jobagent.llm;

import java.util.List;

public interface LlmClient {
    String chat(List<ChatMessage> messages);
}
```

- [ ] **Step 3: 创建 DeepSeekClient（OpenAI 兼容 + 重试）**

`src/main/java/com/jobagent/llm/DeepSeekClient.java`

```java
package com.jobagent.llm;

import com.jobagent.common.BizException;
import com.jobagent.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient implements LlmClient {

    private final LlmProperties llmProperties;
    private final RestTemplate restTemplate;

    @Override
    public String chat(List<ChatMessage> messages) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return doChat(messages);
            } catch (Exception e) {
                last = e;
                log.warn("LLM 调用失败（第 {} 次）: {}", attempt, e.getMessage());
            }
        }
        throw new BizException("大模型调用失败: " + last.getMessage());
    }

    private String doChat(List<ChatMessage> messages) {
        String url = llmProperties.getBaseUrl() + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", llmProperties.getModel());
        body.put("messages", messages);
        body.put("temperature", llmProperties.getTemperature());
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmProperties.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<String, Object> resp = restTemplate.postForObject(url, entity, Map.class);

        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/
git commit -m "feat: 添加 LLM 适配层（DeepSeek OpenAI 兼容 + 重试）"
git push
```

---

## Task 4: 插件式工具体系

**Files:**
- Create: `src/main/java/com/jobagent/tool/Tool.java`
- Create: `src/main/java/com/jobagent/tool/ToolRegistry.java`
- Create: `src/main/java/com/jobagent/tool/StudyPlanTool.java`
- Create: `src/main/java/com/jobagent/tool/InterviewQuestionTool.java`
- Test: `src/test/java/com/jobagent/tool/ToolRegistryTest.java`
- Test: `src/test/java/com/jobagent/tool/StudyPlanToolTest.java`
- Test: `src/test/java/com/jobagent/tool/InterviewQuestionToolTest.java`

- [ ] **Step 1: 写失败测试（注册表）**

`src/test/java/com/jobagent/tool/ToolRegistryTest.java`

```java
package com.jobagent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private final ToolRegistry registry =
            new ToolRegistry(List.of(new StudyPlanTool(), new InterviewQuestionTool()));

    @Test
    void findExisting() {
        assertNotNull(registry.find("study_plan"));
        assertNotNull(registry.find("interview_question"));
    }

    @Test
    void findMissingReturnsNull() {
        assertNull(registry.find("no_such_tool"));
    }

    @Test
    void getAllHasTwoTools() {
        assertEquals(2, registry.getAll().size());
    }
}
```

- [ ] **Step 2: 写失败测试（学习计划工具）**

`src/test/java/com/jobagent/tool/StudyPlanToolTest.java`

```java
package com.jobagent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StudyPlanToolTest {

    @Test
    void executeReturnsPhasedPlan() {
        StudyPlanTool tool = new StudyPlanTool();
        String result = tool.execute(Map.of("targetJob", "Java后端", "days", 30));
        assertTrue(result.contains("30 天"));
        assertTrue(result.contains("第一阶段"));
        assertTrue(result.contains("第三阶段"));
    }
}
```

- [ ] **Step 3: 写失败测试（面试题工具）**

`src/test/java/com/jobagent/tool/InterviewQuestionToolTest.java`

```java
package com.jobagent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewQuestionToolTest {

    @Test
    void executeReturnsQuestions() {
        InterviewQuestionTool tool = new InterviewQuestionTool();
        String result = tool.execute(Map.of("topic", "Redis"));
        assertTrue(result.contains("缓存穿透"));
    }

    @Test
    void executeUnknownTopicReturnsHint() {
        InterviewQuestionTool tool = new InterviewQuestionTool();
        String result = tool.execute(Map.of("topic", "未知"));
        assertTrue(result.contains("暂未收录"));
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `mvn -q -Dtest='ToolRegistryTest,StudyPlanToolTest,InterviewQuestionToolTest' test`
Expected: 编译失败（相关类不存在）

- [ ] **Step 5: 实现 Tool 接口**

`src/main/java/com/jobagent/tool/Tool.java`

```java
package com.jobagent.tool;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    String parametersSchema();
    String execute(Map<String, Object> params);
}
```

- [ ] **Step 6: 实现 ToolRegistry**

`src/main/java/com/jobagent/tool/ToolRegistry.java`

```java
package com.jobagent.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        for (Tool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public Tool find(String name) {
        return tools.get(name);
    }

    public Collection<Tool> getAll() {
        return tools.values();
    }
}
```

- [ ] **Step 7: 实现 StudyPlanTool**

`src/main/java/com/jobagent/tool/StudyPlanTool.java`

```java
package com.jobagent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StudyPlanTool implements Tool {

    @Override
    public String name() {
        return "study_plan";
    }

    @Override
    public String description() {
        return "根据目标岗位、薄弱点和剩余天数生成分阶段学习计划";
    }

    @Override
    public String parametersSchema() {
        return "{\"targetJob\":\"目标岗位，如 Java后端开发\", \"weakPoints\":\"薄弱点列表\", \"days\":\"剩余天数\"}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String targetJob = String.valueOf(params.getOrDefault("targetJob", "Java后端开发"));
        int days = Integer.parseInt(String.valueOf(params.getOrDefault("days", "30")));
        Object weak = params.get("weakPoints");
        String weakPoints = weak == null ? "Java基础、SpringBoot" : String.valueOf(weak);

        int phase = days / 3;

        StringBuilder sb = new StringBuilder();
        sb.append("为你生成 ").append(days).append(" 天 ").append(targetJob).append(" 学习计划：\n\n");

        sb.append("【第一阶段 基础夯实】第 1-").append(phase).append(" 天：\n");
        sb.append("  - 复习 Java 核心（集合、并发、JVM、异常）\n");
        sb.append("  - 每天 2 小时刷 LeetCode 简单题\n\n");

        sb.append("【第二阶段 框架进阶】第 ").append(phase + 1).append("-").append(phase * 2).append(" 天：\n");
        sb.append("  - SpringBoot 自动装配、AOP、事务\n");
        sb.append("  - MySQL 索引、事务；Redis 缓存\n\n");

        sb.append("【第三阶段 项目与冲刺】第 ").append(phase * 2 + 1).append("-").append(days).append(" 天：\n");
        sb.append("  - 完善 Agent 项目，梳理项目亮点\n");
        sb.append("  - 针对薄弱点：").append(weakPoints).append(" 专项补强\n");
        sb.append("  - 模拟面试 + 高频面试题\n");

        return sb.toString();
    }
}
```

- [ ] **Step 8: 实现 InterviewQuestionTool**

`src/main/java/com/jobagent/tool/InterviewQuestionTool.java`

```java
package com.jobagent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InterviewQuestionTool implements Tool {

    private static final Map<String, String> QUESTIONS = new LinkedHashMap<>();

    static {
        QUESTIONS.put("Java基础", "1. HashMap 底层原理？ 2. JVM 内存模型？ 3. == 与 equals 区别？");
        QUESTIONS.put("SpringBoot", "1. 自动装配原理？ 2. Bean 生命周期？ 3. AOP 实现原理？");
        QUESTIONS.put("Redis", "1. 缓存穿透/击穿/雪崩及解决方案？ 2. RDB 与 AOF 持久化？ 3. 分布式锁实现？");
    }

    @Override
    public String name() {
        return "interview_question";
    }

    @Override
    public String description() {
        return "根据知识点返回 Java/SpringBoot/Redis 高频面试题";
    }

    @Override
    public String parametersSchema() {
        return "{\"topic\":\"知识点，如 Java基础/SpringBoot/Redis\"}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String topic = String.valueOf(params.getOrDefault("topic", "Java基础"));
        String q = QUESTIONS.get(topic);
        if (q == null) {
            return "暂未收录「" + topic + "」的面试题，可选题：Java基础、SpringBoot、Redis";
        }
        return "「" + topic + "」高频面试题：\n" + q;
    }
}
```

- [ ] **Step 9: 跑测试确认通过**

Run: `mvn -q -Dtest='ToolRegistryTest,StudyPlanToolTest,InterviewQuestionToolTest' test`
Expected: BUILD SUCCESS，7 个测试通过

- [ ] **Step 10: 提交**

```bash
git add src/
git commit -m "feat: 实现插件式工具体系（工具接口+注册表+2个演示工具）"
git push
```

---

## Task 5: CoT 解析器与提示词

**Files:**
- Create: `src/main/java/com/jobagent/agent/cot/CotResult.java`
- Create: `src/main/java/com/jobagent/agent/cot/CotParser.java`
- Create: `src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java`
- Test: `src/test/java/com/jobagent/agent/cot/CotParserTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/jobagent/agent/cot/CotParserTest.java`

```java
package com.jobagent.agent.cot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CotParserTest {

    private final CotParser parser = new CotParser(new ObjectMapper());

    @Test
    void parseToolCall() {
        String raw = "{\"thinking\":\"需要制定计划\",\"tool\":\"study_plan\",\"params\":{\"days\":30}}";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.TOOL_CALL, r.getType());
        assertEquals("study_plan", r.getTool());
        assertEquals(30, r.getParams().get("days"));
    }

    @Test
    void parseFinalAnswer() {
        String raw = "{\"thinking\":\"直接回答\",\"final_answer\":\"你好\"}";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.FINAL_ANSWER, r.getType());
        assertEquals("你好", r.getFinalAnswer());
    }

    @Test
    void parseWithMarkdownFence() {
        String raw = "```json\n{\"thinking\":\"t\",\"final_answer\":\"ok\"}\n```";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.FINAL_ANSWER, r.getType());
        assertEquals("ok", r.getFinalAnswer());
    }

    @Test
    void parseInvalidThrows() {
        assertThrows(BizException.class, () -> parser.parse("not json"));
    }

    @Test
    void parseMissingFieldsThrows() {
        assertThrows(BizException.class, () -> parser.parse("{\"thinking\":\"x\"}"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=CotParserTest test`
Expected: 编译失败（`CotParser` 等类不存在）

- [ ] **Step 3: 实现 CotResult**

`src/main/java/com/jobagent/agent/cot/CotResult.java`

```java
package com.jobagent.agent.cot;

import lombok.Data;

import java.util.Map;

@Data
public class CotResult {

    public enum Type { TOOL_CALL, FINAL_ANSWER }

    private Type type;
    private String thinking;
    private String tool;
    private Map<String, Object> params;
    private String finalAnswer;

    public static CotResult toolCall(String thinking, String tool, Map<String, Object> params) {
        CotResult r = new CotResult();
        r.type = Type.TOOL_CALL;
        r.thinking = thinking;
        r.tool = tool;
        r.params = params;
        return r;
    }

    public static CotResult finalAnswer(String thinking, String finalAnswer) {
        CotResult r = new CotResult();
        r.type = Type.FINAL_ANSWER;
        r.thinking = thinking;
        r.finalAnswer = finalAnswer;
        return r;
    }
}
```

- [ ] **Step 4: 实现 CotParser**

`src/main/java/com/jobagent/agent/cot/CotParser.java`

```java
package com.jobagent.agent.cot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CotParser {

    private final ObjectMapper objectMapper;

    public CotResult parse(String raw) {
        String json = extractJson(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String thinking = node.path("thinking").asText("");

            if (node.has("final_answer")) {
                return CotResult.finalAnswer(thinking, node.path("final_answer").asText());
            }
            if (node.has("tool")) {
                String tool = node.path("tool").asText();
                JsonNode paramsNode = node.path("params");
                Map<String, Object> params = objectMapper.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {});
                return CotResult.toolCall(thinking, tool, params);
            }
            throw new BizException("CoT 输出缺少 tool 或 final_answer 字段");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("CoT JSON 解析失败: " + e.getMessage());
        }
    }

    private String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        return s;
    }
}
```

- [ ] **Step 5: 实现 CotPromptBuilder**

`src/main/java/com/jobagent/agent/cot/CotPromptBuilder.java`

```java
package com.jobagent.agent.cot;

import com.jobagent.tool.Tool;
import com.jobagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CotPromptBuilder {

    private final ToolRegistry toolRegistry;

    public String build() {
        String tools = toolRegistry.getAll().stream()
                .map(t -> String.format("- %s: %s；参数：%s",
                        t.name(), t.description(), t.parametersSchema()))
                .collect(Collectors.joining("\n"));

        return """
                你是一个求职规划智能 Agent，帮助软件工程应届生完成求职规划。

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
                """.formatted(tools);
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -q -Dtest=CotParserTest test`
Expected: BUILD SUCCESS，5 个测试通过

- [ ] **Step 7: 提交**

```bash
git add src/
git commit -m "feat: 实现自研 CoT 结构化解析器与提示词构建"
git push
```

---

## Task 6: 内存会话存储

**Files:**
- Create: `src/main/java/com/jobagent/session/SessionStore.java`
- Create: `src/main/java/com/jobagent/session/InMemorySessionStore.java`
- Test: `src/test/java/com/jobagent/session/InMemorySessionStoreTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/jobagent/session/InMemorySessionStoreTest.java`

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySessionStoreTest {

    @Test
    void appendAndGet() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "hi"));
        store.append("s1", new ChatMessage("assistant", "hello"));
        assertEquals(2, store.getMessages("s1").size());
    }

    @Test
    void getMissingReturnsEmpty() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertTrue(store.getMessages("nope").isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=InMemorySessionStoreTest test`
Expected: 编译失败（`SessionStore` 等类不存在）

- [ ] **Step 3: 实现 SessionStore 接口与内存实现**

`src/main/java/com/jobagent/session/SessionStore.java`

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;

import java.util.List;

public interface SessionStore {
    List<ChatMessage> getMessages(String sessionId);
    void append(String sessionId, ChatMessage message);
}
```

`src/main/java/com/jobagent/session/InMemorySessionStore.java`

```java
package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionStore implements SessionStore {

    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(String sessionId) {
        return store.getOrDefault(sessionId, new ArrayList<>());
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=InMemorySessionStoreTest test`
Expected: BUILD SUCCESS，2 个测试通过

- [ ] **Step 5: 提交**

```bash
git add src/
git commit -m "feat: 实现内存会话存储（预留接口，后续换 Redis/MySQL）"
git push
```

---

## Task 7: 自研 Agent 主循环

**Files:**
- Create: `src/main/java/com/jobagent/agent/AgentStep.java`
- Create: `src/main/java/com/jobagent/agent/AgentResult.java`
- Create: `src/main/java/com/jobagent/agent/AgentEvent.java`
- Create: `src/main/java/com/jobagent/agent/AgentLoop.java`
- Test: `src/test/java/com/jobagent/agent/AgentLoopTest.java`

- [ ] **Step 1: 写失败测试（mock LLM 验证循环）**

`src/test/java/com/jobagent/agent/AgentLoopTest.java`

```java
package com.jobagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.InMemorySessionStore;
import com.jobagent.session.SessionStore;
import com.jobagent.tool.InterviewQuestionTool;
import com.jobagent.tool.StudyPlanTool;
import com.jobagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    @Test
    void runCallsToolThenFinishes() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("{\"thinking\":\"需要计划\",\"tool\":\"study_plan\",\"params\":{\"days\":30}}")
                .thenReturn("{\"thinking\":\"已生成\",\"final_answer\":\"计划完成\"}");

        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool(), new InterviewQuestionTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();

        AgentLoop loop = new AgentLoop(llm, parser, promptBuilder, registry, sessionStore);

        AgentResult result = loop.run("s1", "制定学习计划", null);

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

        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();

        AgentLoop loop = new AgentLoop(llm, parser, promptBuilder, registry, sessionStore);

        AgentResult result = loop.run("s2", "随便", null);

        assertEquals("完成", result.getFinalAnswer());
        assertEquals(0, result.getSteps().size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=AgentLoopTest test`
Expected: 编译失败（`AgentLoop` 等类不存在）

- [ ] **Step 3: 实现 AgentStep / AgentResult / AgentEvent**

`src/main/java/com/jobagent/agent/AgentStep.java`

```java
package com.jobagent.agent;

import lombok.Data;

@Data
public class AgentStep {
    private int index;
    private String thinking;
    private String tool;
    private String params;
    private String result;
}
```

`src/main/java/com/jobagent/agent/AgentResult.java`

```java
package com.jobagent.agent;

import lombok.Data;

import java.util.List;

@Data
public class AgentResult {
    private String finalAnswer;
    private List<AgentStep> steps;
}
```

`src/main/java/com/jobagent/agent/AgentEvent.java`

```java
package com.jobagent.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentEvent {
    private String type;    // thinking / tool_call / tool_result
    private String content;
}
```

- [ ] **Step 4: 实现 AgentLoop**

`src/main/java/com/jobagent/agent/AgentLoop.java`

```java
package com.jobagent.agent;

import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.agent.cot.CotResult;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
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

    public AgentResult run(String sessionId, String userInput, Consumer<AgentEvent> eventSink) {
        List<ChatMessage> history = sessionStore.getMessages(sessionId);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", promptBuilder.build()));
        messages.addAll(history);
        messages.add(new ChatMessage("user", userInput));

        List<AgentStep> steps = new ArrayList<>();
        String finalAnswer = null;

        int stepNo = 0;
        while (stepNo < MAX_STEPS) {
            stepNo++;

            String raw = llmClient.chat(messages);
            messages.add(new ChatMessage("assistant", raw));

            CotResult cot = cotParser.parse(raw);
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

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -Dtest=AgentLoopTest test`
Expected: BUILD SUCCESS，2 个测试通过

- [ ] **Step 6: 提交**

```bash
git add src/
git commit -m "feat: 实现自研 Agent 主循环（思考→调工具→回填→判断）"
git push
```

---

## Task 8: SSE 控制器与前端页面

**Files:**
- Create: `src/main/java/com/jobagent/web/ChatController.java`
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/app.js`

- [ ] **Step 1: 实现 ChatController（SSE）**

`src/main/java/com/jobagent/web/ChatController.java`

```java
package com.jobagent.web;

import com.jobagent.agent.AgentLoop;
import com.jobagent.agent.AgentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final AgentLoop agentLoop;
    private final Executor agentExecutor;

    @GetMapping("/api/chat/stream")
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(defaultValue = "default") String sessionId) {
        SseEmitter emitter = new SseEmitter(120_000L);

        agentExecutor.execute(() -> {
            try {
                AgentResult result = agentLoop.run(sessionId, message, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.getType())
                                .data(String.valueOf(event.getContent())));
                    } catch (Exception ignored) {
                    }
                });
                emitter.send(SseEmitter.event().name("final_answer").data(result.getFinalAnswer()));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                log.error("Agent 执行异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }
}
```

- [ ] **Step 2: 实现前端 index.html**

`src/main/resources/static/index.html`

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>求职规划智能 Agent</title>
    <style>
        body { font-family: sans-serif; max-width: 800px; margin: 40px auto; padding: 0 20px; }
        #chat { border: 1px solid #ddd; border-radius: 8px; padding: 16px; min-height: 320px; }
        .user { color: #333; margin: 8px 0; }
        .thinking { color: #999; font-style: italic; margin: 4px 0; }
        .tool_call { color: #2563eb; margin: 4px 0; }
        .tool_result { color: #16a34a; white-space: pre-wrap; margin: 4px 0; }
        .final_answer { color: #111; font-weight: bold; margin: 8px 0; }
        .error { color: #dc2626; margin: 4px 0; }
        #input { width: calc(100% - 90px); padding: 8px; }
        button { padding: 8px 16px; cursor: pointer; }
    </style>
</head>
<body>
    <h2>求职规划智能 Agent</h2>
    <div id="chat"></div>
    <div style="margin-top: 12px;">
        <input id="input" placeholder="输入指令，如：帮我制定30天Java后端学习计划" />
        <button onclick="send()">发送</button>
    </div>
    <script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 3: 实现前端 app.js**

`src/main/resources/static/app.js`

```javascript
const sessionId = 'demo-' + Date.now();

function addLine(cls, text) {
    const div = document.createElement('div');
    div.className = cls;
    div.textContent = text;
    document.getElementById('chat').appendChild(div);
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
```

- [ ] **Step 4: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/
git commit -m "feat: 添加 SSE 流式控制器与极简 Web 对话页"
git push
```

---

## Task 9: 端到端验收

- [ ] **Step 1: 设置 API Key 并启动**

```bash
export DEEPSEEK_API_KEY=你的key
mvn spring-boot:run
```

Expected: 应用在 `http://localhost:8080` 启动，无报错。

- [ ] **Step 2: 浏览器验收**

打开 `http://localhost:8080`，依次验证：

1. 输入「帮我制定一个 30 天 Java 后端求职学习计划」→ 应流式看到 `思考 → 调用工具 study_plan → 工具结果 → 回答`。
2. 输入「问几道 Redis 面试题」→ 应看到调用 `interview_question` 工具（验证工具选择）。
3. 同一页面继续追问（如「再问几道 SpringBoot 的」）→ 能关联上文（验证多轮记忆）。

- [ ] **Step 3: 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，全部单测通过。

- [ ] **Step 4: 提交（如有改动）**

```bash
git add -A
git commit -m "docs: 完成切片1端到端验收"
git push
```

---

## 完成定义（切片 1）

- [ ] `mvn test` 全部通过
- [ ] 浏览器可输入指令，流式展示「思考/工具调用/结果/最终答案」
- [ ] 复杂指令能拆解并调用 `study_plan` 工具；换指令能正确选择 `interview_question` 工具
- [ ] 多轮对话能关联上文（内存会话生效）
- [ ] 代码已推送远端 `origin/main`
