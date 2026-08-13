package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySessionStoreTest {

    @Test
    void appendAndGet() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "hi"));
        store.append("s1", new ChatMessage("assistant", "hello"));
        assertEquals(2, store.getMessages("s1").size());
    }

    @Test
    void getMissingReturnsEmpty() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertTrue(store.getMessages("nope").isEmpty());
    }

    @Test
    void getMessagesFiltersSummary() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "hi"));
        store.replaceSummary("s1", "摘要");
        store.append("s1", new ChatMessage("assistant", "hello"));
        List<ChatMessage> result = store.getMessages("s1");
        assertEquals(2, result.size());
        assertEquals("hi", result.get(0).getContent());
        assertEquals("hello", result.get(1).getContent());
    }

    @Test
    void getNormalMessagesFiltersSummaryAndArchived() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "m1"));
        store.append("s1", new ChatMessage("user", "m2"));
        store.append("s1", new ChatMessage("user", "m3"));
        store.archiveOlder("s1", 1);
        store.replaceSummary("s1", "摘要");
        List<ChatMessage> result = store.getNormalMessages("s1");
        assertEquals(1, result.size());
        assertEquals("m3", result.get(0).getContent());
    }

    @Test
    void getLatestSummaryReturnsNullWhenNone() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertNull(store.getLatestSummary("s1"));
    }

    @Test
    void getLatestSummaryReturnsLatest() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.replaceSummary("s1", "第一版");
        store.replaceSummary("s1", "第二版");
        assertEquals("第二版", store.getLatestSummary("s1"));
    }

    @Test
    void countNormalCharsSumsContentLength() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "你好"));
        store.append("s1", new ChatMessage("assistant", "hello world"));
        assertEquals(13, store.countNormalChars("s1"));
    }

    @Test
    void archiveOlderKeepsRecent() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.append("s1", new ChatMessage("user", "m1"));
        store.append("s1", new ChatMessage("user", "m2"));
        store.append("s1", new ChatMessage("user", "m3"));
        store.append("s1", new ChatMessage("user", "m4"));
        store.archiveOlder("s1", 2);
        List<ChatMessage> normals = store.getNormalMessages("s1");
        assertEquals(2, normals.size());
        assertEquals("m3", normals.get(0).getContent());
        assertEquals("m4", normals.get(1).getContent());
    }
}
