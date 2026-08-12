package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.junit.jupiter.api.Test;

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
}
