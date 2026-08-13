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
