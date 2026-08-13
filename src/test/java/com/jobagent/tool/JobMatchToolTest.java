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
