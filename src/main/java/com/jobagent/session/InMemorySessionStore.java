package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemorySessionStore implements SessionStore {

    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(String sessionId) {
        return store.getOrDefault(sessionId, new ArrayList<>());
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }
}
