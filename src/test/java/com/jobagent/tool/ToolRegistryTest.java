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
