package com.jobagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.InMemorySessionStore;
import com.jobagent.session.SessionStore;
import com.jobagent.tool.InterviewQuestionTool;
import com.jobagent.tool.StudyPlanTool;
import com.jobagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    @Test
    void runCallsToolThenFinishes() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("{\"thinking\":\"需要计划\",\"tool\":\"study_plan\",\"params\":{\"days\":30}}")
                .thenReturn("{\"thinking\":\"已生成\",\"final_answer\":\"计划完成\"}");

        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool(), new InterviewQuestionTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();

        AgentLoop loop = new AgentLoop(llm, parser, promptBuilder, registry, sessionStore);

        AgentResult result = loop.run("s1", "制定学习计划", null);

        assertEquals("计划完成", result.getFinalAnswer());
        assertEquals(1, result.getSteps().size());
        assertEquals("study_plan", result.getSteps().get(0).getTool());
        verify(llm, times(2)).chat(anyList());
    }

    @Test
    void unknownToolRetries() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("{\"thinking\":\"试试\",\"tool\":\"nope\",\"params\":{}}")
                .thenReturn("{\"thinking\":\"换一个\",\"final_answer\":\"完成\"}");

        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();

        AgentLoop loop = new AgentLoop(llm, parser, promptBuilder, registry, sessionStore);

        AgentResult result = loop.run("s2", "随便", null);

        assertEquals("完成", result.getFinalAnswer());
        assertEquals(0, result.getSteps().size());
    }
}
