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
