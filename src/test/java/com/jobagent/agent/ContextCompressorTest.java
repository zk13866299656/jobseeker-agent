package com.jobagent.agent;

import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.SessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ContextCompressorTest {

    @Test
    void belowThresholdDoesNothing() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(8000);

        new ContextCompressor(llm, store).compressIfNeeded("s1");

        verify(llm, never()).chat(anyList());
        verify(store, never()).replaceSummary(anyString(), anyString());
        verify(store, never()).archiveOlder(anyString(), anyInt());
    }

    @Test
    void aboveThresholdSummarizesAndArchives() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(8001);
        when(store.getLatestSummary("s1")).thenReturn("旧摘要");
        when(store.getNormalMessages("s1")).thenReturn(List.of(
                new ChatMessage("user", "你好"),
                new ChatMessage("assistant", "你好，我是求职助手")));
        when(llm.chat(anyList())).thenReturn("新摘要内容");

        new ContextCompressor(llm, store).compressIfNeeded("s1");

        verify(store).replaceSummary("s1", "新摘要内容");
        verify(store).archiveOlder("s1", ContextCompressor.KEEP_RECENT);
    }

    @Test
    void llmFailureIsSwallowed() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(9000);
        when(llm.chat(anyList())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> new ContextCompressor(llm, store).compressIfNeeded("s1"));

        verify(store, never()).replaceSummary(anyString(), anyString());
        verify(store, never()).archiveOlder(anyString(), anyInt());
    }

    @Test
    void blankSummaryDoesNotStore() {
        LlmClient llm = mock(LlmClient.class);
        SessionStore store = mock(SessionStore.class);
        when(store.countNormalChars("s1")).thenReturn(9000);
        when(llm.chat(anyList())).thenReturn("   ");

        new ContextCompressor(llm, store).compressIfNeeded("s1");

        verify(store, never()).replaceSummary(anyString(), anyString());
        verify(store, never()).archiveOlder(anyString(), anyInt());
    }

    @Test
    void buildSummaryMessagesIncludesOldSummaryAndDialog() {
        ContextCompressor c = new ContextCompressor(mock(LlmClient.class), mock(SessionStore.class));
        List<ChatMessage> msgs = c.buildSummaryMessages("旧摘要", List.of(
                new ChatMessage("user", "我想找后端")));

        assertEquals(2, msgs.size());
        assertEquals("system", msgs.get(0).getRole());
        assertEquals("user", msgs.get(1).getRole());
        assertTrue(msgs.get(1).getContent().contains("旧摘要"));
        assertTrue(msgs.get(1).getContent().contains("我想找后端"));
    }
}
