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
