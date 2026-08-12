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
