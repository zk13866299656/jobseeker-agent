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
