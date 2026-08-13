# 切片5 工具集扩展 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 3 个读取长期记忆的工具（岗位匹配/简历优化/错题复盘），并把 `Tool.execute` 接口升级为带 `userId`。

**Architecture:** 改 `Tool` 接口签名让工具能拿到用户身份；三个新工具注入 `MemoryService` 读记忆，保持纯 Java 规则（不内部调 LLM）、只读不写记忆。

**Tech Stack:** Spring Boot 3.3.5 + Java 17 + Maven + Lombok + JUnit5 + Mockito

**Spec:** docs/superpowers/specs/2026-08-13-tool-expansion-design.md

## Global Constraints

- SpringBoot 3.3.5 + Java 17 + Maven，Lombok 已启用。
- 纯手搓 Agent，不引入 LangChain / SpringAI / 任何新依赖。
- 工具保持纯 Java 规则：`execute` 内不得调用 `LlmClient`。
- 工具只读记忆：只能调 `memoryService.load(userId)`，不得写 `user_memory`。
- 每个 task 结束必须 `mvn test` 全绿，且 commit message 用中文。
- 新工具通过 `@Component` + `@RequiredArgsConstructor` 注入 `MemoryService`，自动注册进 `ToolRegistry`，无需改 `CotPromptBuilder` 或 `ToolRegistry`。

---

### Task 1: Tool 接口加 userId 参数

**Files:**
- Modify: `src/main/java/com/jobagent/tool/Tool.java`
- Modify: `src/main/java/com/jobagent/tool/StudyPlanTool.java`
- Modify: `src/main/java/com/jobagent/tool/InterviewQuestionTool.java`
- Modify: `src/main/java/com/jobagent/agent/AgentLoop.java`
- Modify: `src/test/java/com/jobagent/tool/StudyPlanToolTest.java`
- Modify: `src/test/java/com/jobagent/tool/InterviewQuestionToolTest.java`

**Interfaces:**
- Consumes: 无（本计划第一个 task）。
- Produces: `Tool.execute(String userId, Map<String,Object> params)` 新签名。后续 Task 2/3/4 的新工具实现此签名；`AgentLoop` 以 `tool.execute(userId, cot.getParams())` 调用。

- [ ] **Step 1: 改接口签名**

`src/main/java/com/jobagent/tool/Tool.java` 中，把：

```java
public interface Tool {
    String name();
    String description();
    String parametersSchema();
    String execute(Map<String, Object> params);
}
```

改为：

```java
public interface Tool {
    String name();
    String description();
    String parametersSchema();
    String execute(String userId, Map<String, Object> params);
}
```

- [ ] **Step 2: 同步两个老工具的签名**

`StudyPlanTool.java` 把：

```java
    @Override
    public String execute(Map<String, Object> params) {
```

改为：

```java
    @Override
    public String execute(String userId, Map<String, Object> params) {
```

`InterviewQuestionTool.java` 同样把 `public String execute(Map<String, Object> params) {` 改为 `public String execute(String userId, Map<String, Object> params) {`（方法体不动，`userId` 忽略不用）。

- [ ] **Step 3: 改主循环调用点**

`src/main/java/com/jobagent/agent/AgentLoop.java` 第 81 行附近，把：

```java
                toolResult = tool.execute(cot.getParams());
```

改为：

```java
                toolResult = tool.execute(userId, cot.getParams());
```

- [ ] **Step 4: 改两个老工具测试的调用点**

`StudyPlanToolTest.java` 把：

```java
        String result = tool.execute(Map.of("targetJob", "Java后端", "days", 30));
```

改为：

```java
        String result = tool.execute("u1", Map.of("targetJob", "Java后端", "days", 30));
```

`InterviewQuestionToolTest.java` 有两处 `tool.execute(Map.of(...))`，都改为 `tool.execute("u1", Map.of(...))`：

```java
        String result = tool.execute("u1", Map.of("topic", "Redis"));
```

```java
        String result = tool.execute("u1", Map.of("topic", "未知"));
```

> 注：`ToolRegistryTest` 与 `AgentLoopTest` 不直接调 `execute`，且 `StudyPlanTool`/`InterviewQuestionTool` 仍是无参构造，无需改动。

- [ ] **Step 5: 跑全量测试确认编译通过且无回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS，全部测试通过（无编译错误）。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jobagent/tool/Tool.java src/main/java/com/jobagent/tool/StudyPlanTool.java src/main/java/com/jobagent/tool/InterviewQuestionTool.java src/main/java/com/jobagent/agent/AgentLoop.java src/test/java/com/jobagent/tool/StudyPlanToolTest.java src/test/java/com/jobagent/tool/InterviewQuestionToolTest.java
git commit -m "feat: Tool.execute 接口增加 userId 参数"
```

---

### Task 2: JobMatchTool（岗位匹配）

**Files:**
- Create: `src/main/java/com/jobagent/tool/JobMatchTool.java`
- Test: `src/test/java/com/jobagent/tool/JobMatchToolTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Tool.execute(String userId, Map<String,Object> params)`；`MemoryService.load(String userId)` 返回 `List<UserMemory>`（`UserMemory` 有 `getType()`/`getContent()`）。
- Produces: `JobMatchTool`（`name()="job_match"`），注入 `MemoryService`。无下游依赖。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/jobagent/tool/JobMatchToolTest.java`：

```java
package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobMatchToolTest {

    @Test
    void matchesTargetRoleAndListsMissingSkills() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(
                new UserMemory("target_role", "Java后端"),
                new UserMemory("skill", "Java、MySQL")));
        JobMatchTool tool = new JobMatchTool(memoryService);

        String result = tool.execute("u1", Map.of());

        assertTrue(result.contains("Java后端"));
        assertTrue(result.contains("缺"));
        assertTrue(result.contains("SpringBoot"));
        assertTrue(result.contains("Redis"));
    }

    @Test
    void roleParamOverridesTarget() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(
                new UserMemory("target_role", "Java后端")));
        JobMatchTool tool = new JobMatchTool(memoryService);

        String result = tool.execute("u1", Map.of("role", "前端"));

        assertTrue(result.contains("前端"));
    }

    @Test
    void emptyMemoryStillReturnsText() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of());
        JobMatchTool tool = new JobMatchTool(memoryService);

        String result = tool.execute("u1", Map.of());

        assertTrue(result.contains("岗位匹配结果"));
        assertTrue(result.contains("匹配度"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=JobMatchToolTest test`
Expected: FAIL（`JobMatchTool` 不存在，编译失败）。

- [ ] **Step 3: 实现 JobMatchTool**

创建 `src/main/java/com/jobagent/tool/JobMatchTool.java`：

```java
package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JobMatchTool implements Tool {

    private static final Map<String, List<String>> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put("Java后端", List.of("Java", "SpringBoot", "MySQL", "Redis", "并发/JVM", "网络"));
        ROLES.put("前端", List.of("JavaScript", "Vue/React", "HTML/CSS", "HTTP/网络", "浏览器"));
        ROLES.put("测试开发", List.of("Java/Python", "测试理论", "自动化测试", "SQL", "Linux"));
        ROLES.put("大数据", List.of("Java/Scala", "Hadoop", "Spark", "SQL", "分布式"));
        ROLES.put("运维/DevOps", List.of("Linux", "Docker", "K8s", "网络", "Shell/Python", "监控"));
        ROLES.put("客户端", List.of("Java/Kotlin", "移动框架", "网络", "数据结构"));
        ROLES.put("算法", List.of("Python/C++", "数据结构与算法", "机器学习", "数学"));
    }

    private final MemoryService memoryService;

    @Override
    public String name() {
        return "job_match";
    }

    @Override
    public String description() {
        return "根据用户目标岗位和技能，匹配软工应届生岗位方向并指出技能差距";
    }

    @Override
    public String parametersSchema() {
        return "{\"role\":\"可选，指定方向如 后端/前端/测试/大数据，不传则用记忆中的目标岗位\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        List<UserMemory> memories = memoryService.load(userId);
        String targetRole = memoryValue(memories, "target_role");
        List<String> skills = splitSkills(memoryValue(memories, "skill"));

        String role = params.get("role") == null ? normalizeRole(targetRole) : String.valueOf(params.get("role"));

        StringBuilder sb = new StringBuilder();
        sb.append("岗位匹配结果：\n\n");

        if (role != null && ROLES.containsKey(role)) {
            sb.append("你的目标方向：").append(role).append("\n");
            sb.append("该方向所需技能：").append(String.join("、", ROLES.get(role))).append("\n");
            List<String> missing = missingSkills(role, skills);
            if (missing.isEmpty()) {
                sb.append("你已掌握该方向全部核心技能，很棒！\n\n");
            } else {
                sb.append("你目前还缺：").append(String.join("、", missing)).append("\n\n");
            }
        }

        sb.append("按你的技能匹配到的岗位方向（匹配度从高到低）：\n");
        for (Map.Entry<String, List<String>> e : ROLES.entrySet()) {
            String r = e.getKey();
            List<String> required = e.getValue();
            long hit = required.stream().filter(skills::contains).count();
            int pct = required.isEmpty() ? 0 : (int) (hit * 100 / required.size());
            sb.append("- ").append(r).append("：匹配度 ").append(pct).append("%");
            List<String> miss = missingSkills(r, skills);
            if (!miss.isEmpty()) {
                sb.append("（缺：").append(String.join("、", miss)).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n建议：优先补齐目标方向缺失的核心技能，具体学习顺序可结合 study_plan 工具制定计划。");
        return sb.toString();
    }

    private String memoryValue(List<UserMemory> memories, String type) {
        return memories.stream()
                .filter(m -> type.equals(m.getType()))
                .map(UserMemory::getContent)
                .findFirst().orElse(null);
    }

    private List<String> splitSkills(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，、;；]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String normalizeRole(String targetRole) {
        if (targetRole == null) {
            return null;
        }
        for (String r : ROLES.keySet()) {
            if (targetRole.contains(r)) {
                return r;
            }
        }
        return null;
    }

    private List<String> missingSkills(String role, List<String> skills) {
        return ROLES.get(role).stream().filter(s -> !skills.contains(s)).toList();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=JobMatchToolTest test`
Expected: PASS（3 个测试全过）。

- [ ] **Step 5: 跑全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jobagent/tool/JobMatchTool.java src/test/java/com/jobagent/tool/JobMatchToolTest.java
git commit -m "feat: 新增 job_match 岗位匹配工具（读记忆）"
```

---

### Task 3: ResumeImproveTool（简历优化）

**Files:**
- Create: `src/main/java/com/jobagent/tool/ResumeImproveTool.java`
- Test: `src/test/java/com/jobagent/tool/ResumeImproveToolTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Tool.execute(String userId, Map<String,Object> params)`；`MemoryService.load`。
- Produces: `ResumeImproveTool`（`name()="resume_improve"`）。无下游依赖。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/jobagent/tool/ResumeImproveToolTest.java`：

```java
package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeImproveToolTest {

    @Test
    void outputsStarSectionsWithProfile() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(
                new UserMemory("skill", "Java"),
                new UserMemory("target_role", "Java后端")));
        ResumeImproveTool tool = new ResumeImproveTool(memoryService);

        String result = tool.execute("u1", Map.of());

        assertTrue(result.contains("STAR"));
        assertTrue(result.contains("目标岗位"));
        assertTrue(result.contains("Java后端"));
    }

    @Test
    void rewritesProvidedExperience() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of());
        ResumeImproveTool tool = new ResumeImproveTool(memoryService);

        String result = tool.execute("u1", Map.of("experience", "做过一个 Agent 项目"));

        assertTrue(result.contains("做过一个 Agent 项目"));
        assertTrue(result.contains("情境 Situation"));
        assertTrue(result.contains("结果 Result"));
    }

    @Test
    void emptyMemoryStillReturnsText() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of());
        ResumeImproveTool tool = new ResumeImproveTool(memoryService);

        String result = tool.execute("u1", Map.of());

        assertTrue(result.contains("STAR"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ResumeImproveToolTest test`
Expected: FAIL（`ResumeImproveTool` 不存在）。

- [ ] **Step 3: 实现 ResumeImproveTool**

创建 `src/main/java/com/jobagent/tool/ResumeImproveTool.java`：

```java
package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResumeImproveTool implements Tool {

    private final MemoryService memoryService;

    @Override
    public String name() {
        return "resume_improve";
    }

    @Override
    public String description() {
        return "基于用户技能/进度/目标岗位，用 STAR 法则生成简历要点或改写经历";
    }

    @Override
    public String parametersSchema() {
        return "{\"experience\":\"可选，用户补充的一段项目/实习经历描述\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        List<UserMemory> memories = memoryService.load(userId);
        String skill = memoryValue(memories, "skill");
        String targetRole = memoryValue(memories, "target_role");
        String progress = memoryValue(memories, "progress");
        String experience = params.get("experience") == null ? null : String.valueOf(params.get("experience"));

        StringBuilder sb = new StringBuilder();
        sb.append("简历优化建议（STAR 法则）：\n\n");

        if (experience != null && !experience.isBlank()) {
            sb.append("针对你的这段经历：").append(experience).append("\n");
            sb.append("建议改写为：\n");
            sb.append("- 情境 Situation：一句话交代项目背景与目标\n");
            sb.append("- 任务 Task：你在其中承担的角色与责任\n");
            sb.append("- 行动 Action：你具体做了什么（技术栈/方法）\n");
            sb.append("- 结果 Result：用数据量化产出（性能、效率、规模）\n\n");
        } else {
            sb.append("你当前的画像：\n");
            if (targetRole != null) sb.append("- 目标岗位：").append(targetRole).append("\n");
            if (skill != null) sb.append("- 技能：").append(skill).append("\n");
            if (progress != null) sb.append("- 进度：").append(progress).append("\n");
            sb.append("\n请按 STAR 法则组织你的项目经历，每段经历至少补一个可量化结果。\n");
        }

        sb.append("量化建议：避免「会 XX」「熟悉 XX」这类空泛表达，改成「独立完成 XX 模块，接口 QPS 提升 Y%」这类带数字的说法。");
        return sb.toString();
    }

    private String memoryValue(List<UserMemory> memories, String type) {
        return memories.stream()
                .filter(m -> type.equals(m.getType()))
                .map(UserMemory::getContent)
                .findFirst().orElse(null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ResumeImproveToolTest test`
Expected: PASS（3 个测试全过）。

- [ ] **Step 5: 跑全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jobagent/tool/ResumeImproveTool.java src/test/java/com/jobagent/tool/ResumeImproveToolTest.java
git commit -m "feat: 新增 resume_improve 简历优化工具（读记忆）"
```

---

### Task 4: MistakeReviewTool（错题复盘）

**Files:**
- Create: `src/main/java/com/jobagent/tool/MistakeReviewTool.java`
- Test: `src/test/java/com/jobagent/tool/MistakeReviewToolTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Tool.execute(String userId, Map<String,Object> params)`；`MemoryService.load`。
- Produces: `MistakeReviewTool`（`name()="mistake_review"`）。无下游依赖。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/jobagent/tool/MistakeReviewToolTest.java`：

```java
package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MistakeReviewToolTest {

    @Test
    void outputsReviewCardStructure() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(
                new UserMemory("skill", "Java"),
                new UserMemory("target_role", "Java后端")));
        MistakeReviewTool tool = new MistakeReviewTool(memoryService);

        String result = tool.execute("u1", Map.of("question", "HashMap 底层原理", "myAnswer", "说错了"));

        assertTrue(result.contains("HashMap 底层原理"));
        assertTrue(result.contains("说错了"));
        assertTrue(result.contains("正确思路"));
        assertTrue(result.contains("知识盲区"));
        assertTrue(result.contains("下次怎么做"));
    }

    @Test
    void emptyMemoryAndAnswerStillReturnsText() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of());
        MistakeReviewTool tool = new MistakeReviewTool(memoryService);

        String result = tool.execute("u1", Map.of("question", "某题"));

        assertTrue(result.contains("某题"));
        assertTrue(result.contains("（未提供）"));
        assertTrue(result.contains("正确思路"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=MistakeReviewToolTest test`
Expected: FAIL（`MistakeReviewTool` 不存在）。

- [ ] **Step 3: 实现 MistakeReviewTool**

创建 `src/main/java/com/jobagent/tool/MistakeReviewTool.java`：

```java
package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MistakeReviewTool implements Tool {

    private final MemoryService memoryService;

    @Override
    public String name() {
        return "mistake_review";
    }

    @Override
    public String description() {
        return "针对用户答错的面试题生成结构化复盘框架";
    }

    @Override
    public String parametersSchema() {
        return "{\"question\":\"题目\", \"myAnswer\":\"可选，用户当时的错误回答\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        List<UserMemory> memories = memoryService.load(userId);
        String skill = memoryValue(memories, "skill");
        String targetRole = memoryValue(memories, "target_role");
        String question = params.get("question") == null ? "（未提供题目）" : String.valueOf(params.get("question"));
        String myAnswer = params.get("myAnswer") == null ? null : String.valueOf(params.get("myAnswer"));

        StringBuilder sb = new StringBuilder();
        sb.append("错题复盘卡：\n\n");
        sb.append("1. 题目：").append(question).append("\n");
        if (myAnswer != null && !myAnswer.isBlank()) {
            sb.append("2. 我的错误回答：").append(myAnswer).append("\n");
        } else {
            sb.append("2. 我的错误回答：（未提供）\n");
        }
        sb.append("3. 正确思路：（请结合下面的知识点，讲清正确解法）\n");
        if (skill != null) {
            sb.append("   - 关联你的技能盲区：").append(skill).append("\n");
        }
        if (targetRole != null) {
            sb.append("   - 该题与目标岗位 ").append(targetRole).append(" 的关联：\n");
        }
        sb.append("4. 知识盲区：请指出这道题涉及的考点，以及你哪里理解错了\n");
        sb.append("5. 下次怎么做：给出 1-2 条可执行的巩固动作（如重刷该知识点、做 2 道同类题）\n");
        return sb.toString();
    }

    private String memoryValue(List<UserMemory> memories, String type) {
        return memories.stream()
                .filter(m -> type.equals(m.getType()))
                .map(UserMemory::getContent)
                .findFirst().orElse(null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=MistakeReviewToolTest test`
Expected: PASS（2 个测试全过）。

- [ ] **Step 5: 跑全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/jobagent/tool/MistakeReviewTool.java src/test/java/com/jobagent/tool/MistakeReviewToolTest.java
git commit -m "feat: 新增 mistake_review 错题复盘工具（读记忆）"
```

---

## 完成后的手动验收（E2E，需 DEEPSEEK_API_KEY）

启动应用后，在页面依次验证（会记录成测试文档到 `docs/testing/`）：

1. 说「我想找 Java 后端工作」→ Agent 调用 `job_match`，输出匹配度与技能差距。
2. 贴一段项目经历 → Agent 调用 `resume_improve`，产出 STAR 改写要点。
3. 说「这道题我答错了：HashMap 底层原理」→ Agent 调用 `mistake_review`，产出结构化复盘，最终答案补全「正确思路」。
