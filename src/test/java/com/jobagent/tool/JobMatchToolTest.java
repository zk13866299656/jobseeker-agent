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

    @Test
    void slashTokenCountsAsHitWhenAnyAlternativeMatches() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(
                new UserMemory("target_role", "测试开发"),
                new UserMemory("skill", "Java")));
        JobMatchTool tool = new JobMatchTool(memoryService);

        String result = tool.execute("u1", Map.of());

        // Java 命中 Java/Python，测试开发方向匹配度应为 1/5 = 20%
        assertTrue(result.contains("测试开发：匹配度 20%"));

        // 「你目前还缺」清单不应把 Java/Python 列为缺
        String miss = "你目前还缺：";
        int idx = result.indexOf(miss);
        assertTrue(idx >= 0);
        String missingLine = result.substring(idx, result.indexOf("\n", idx));
        assertFalse(missingLine.contains("Java/Python"));
    }

    @Test
    void roleParamAliasIsNormalized() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of());
        JobMatchTool tool = new JobMatchTool(memoryService);

        String result = tool.execute("u1", Map.of("role", "后端"));

        // 别名「后端」应归一化为真实 key「Java后端」——只有归一化成功才会打印目标方向头
        assertTrue(result.contains("你的目标方向：Java后端"));
    }

    @Test
    void resultsAreSortedByMatchDescending() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(
                new UserMemory("skill", "Python、C++、数据结构与算法、机器学习、数学")));
        JobMatchTool tool = new JobMatchTool(memoryService);

        String result = tool.execute("u1", Map.of());

        // 算法方向 100%，在插入序里排最后，但应按匹配度排到最前；Java后端 0% 应落到其后
        assertTrue(result.indexOf("算法") < result.indexOf("Java后端"));
    }
}
