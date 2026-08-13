package com.jobagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import com.jobagent.session.InMemorySessionStore;
import com.jobagent.session.SessionStore;
import com.jobagent.tool.InterviewQuestionTool;
import com.jobagent.tool.StudyPlanTool;
import com.jobagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    private AgentLoop newLoop(LlmClient llm, MemoryService memoryService, ContextCompressor compressor) {
        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool(), new InterviewQuestionTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        SessionStore sessionStore = new InMemorySessionStore();
        return new AgentLoop(llm, parser, promptBuilder, registry, sessionStore, memoryService, compressor);
    }

    @Test
    void runCallsToolThenFinishes() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("{\"thinking\":\"需要计划\",\"tool\":\"study_plan\",\"params\":{\"days\":30}}")
                .thenReturn("{\"thinking\":\"已生成\",\"final_answer\":\"计划完成\"}");
        MemoryService memoryService = mock(MemoryService.class);

        AgentResult result = newLoop(llm, memoryService, mock(ContextCompressor.class)).run("s1", "u1", "制定学习计划", null);

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
        MemoryService memoryService = mock(MemoryService.class);

        AgentResult result = newLoop(llm, memoryService, mock(ContextCompressor.class)).run("s2", "u1", "随便", null);

        assertEquals("完成", result.getFinalAnswer());
        assertEquals(0, result.getSteps().size());
    }

    @Test
    void parseFailureRetries() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList()))
                .thenReturn("这不是合法JSON")
                .thenReturn("{\"thinking\":\"重试\",\"final_answer\":\"重试成功\"}");
        MemoryService memoryService = mock(MemoryService.class);

        AgentResult result = newLoop(llm, memoryService, mock(ContextCompressor.class)).run("s3", "u1", "测试", null);

        assertEquals("重试成功", result.getFinalAnswer());
        verify(llm, times(2)).chat(anyList());
    }

    @Test
    void injectsMemoryIntoSystemPrompt() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("{\"thinking\":\"ok\",\"final_answer\":\"你好\"}");
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.load("u1")).thenReturn(List.of(new UserMemory("name", "张三")));

        newLoop(llm, memoryService, mock(ContextCompressor.class)).run("s4", "u1", "你好", null);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue();
        assertEquals("system", sent.get(0).getRole());
        assertTrue(sent.get(0).getContent().contains("张三"));
        assertTrue(sent.get(0).getContent().contains("name"));
    }

    @Test
    void runSendsSummaryAndNormalButNotArchived() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("{\"thinking\":\"ok\",\"final_answer\":\"你好\"}");
        MemoryService memoryService = mock(MemoryService.class);

        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s5", new ChatMessage("user", "旧的已归档消息"));
        store.archiveOlder("s5", 0);
        store.replaceSummary("s5", "这是摘要");
        store.append("s5", new ChatMessage("user", "最新消息"));

        CotParser parser = new CotParser(new ObjectMapper());
        ToolRegistry registry = new ToolRegistry(List.of(new StudyPlanTool()));
        CotPromptBuilder promptBuilder = new CotPromptBuilder(registry);
        AgentLoop loop = new AgentLoop(llm, parser, promptBuilder, registry, store, memoryService, mock(ContextCompressor.class));

        loop.run("s5", "u1", "你好", null);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue();
        assertTrue(sent.get(0).getContent().contains("这是摘要"));
        assertTrue(sent.stream().anyMatch(m -> "最新消息".equals(m.getContent())));
        assertTrue(sent.stream().noneMatch(m -> "旧的已归档消息".equals(m.getContent())));
    }

    @Test
    void runCallsCompressAfterAppend() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("{\"thinking\":\"ok\",\"final_answer\":\"你好\"}");
        MemoryService memoryService = mock(MemoryService.class);
        ContextCompressor compressor = mock(ContextCompressor.class);

        newLoop(llm, memoryService, compressor).run("s6", "u1", "你好", null);

        verify(compressor).compressIfNeeded("s6");
    }
}
