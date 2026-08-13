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
