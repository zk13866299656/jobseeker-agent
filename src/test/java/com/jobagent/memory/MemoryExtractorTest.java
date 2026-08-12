package com.jobagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class MemoryExtractorTest {

    private MemoryExtractor newExtractor(LlmClient llm) {
        return new MemoryExtractor(llm, new ObjectMapper());
    }

    @Test
    void parseArrayReturnsMemories() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        List<UserMemory> result = e.parse(
                "[{\"type\":\"name\",\"content\":\"张三\"},{\"type\":\"skill\",\"content\":\"Java\"}]");
        assertEquals(2, result.size());
        assertEquals("name", result.get(0).getType());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void parseEmptyArrayReturnsEmpty() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        assertTrue(e.parse("[]").isEmpty());
    }

    @Test
    void parseObjectNotArrayReturnsEmpty() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        assertTrue(e.parse("{\"type\":\"name\",\"content\":\"张三\"}").isEmpty());
    }

    @Test
    void parseInvalidJsonReturnsEmpty() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        assertTrue(e.parse("这不是JSON").isEmpty());
    }

    @Test
    void parseSkipsBlankEntries() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        List<UserMemory> result = e.parse(
                "[{\"type\":\"name\",\"content\":\"张三\"},{\"type\":\"\",\"content\":\"\"}]");
        assertEquals(1, result.size());
    }

    @Test
    void parseFiltersUnknownTypeAndTrims() {
        MemoryExtractor e = newExtractor(mock(LlmClient.class));
        List<UserMemory> result = e.parse(
                "[{\"type\":\"weakness\",\"content\":\"x\"},{\"type\":\"name\",\"content\":\" 张三 \"}]");
        assertEquals(1, result.size());
        assertEquals("name", result.get(0).getType());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void extractBuildsPromptAndParsesResult() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList())).thenReturn("[{\"type\":\"name\",\"content\":\"张三\"}]");
        MemoryExtractor e = newExtractor(llm);
        List<UserMemory> result = e.extract(List.of(), "我叫张三", "你好张三");
        assertEquals(1, result.size());
        assertEquals("张三", result.get(0).getContent());
    }
}
