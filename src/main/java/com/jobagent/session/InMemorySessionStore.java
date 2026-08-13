package com.jobagent.session;

import com.jobagent.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySessionStore implements SessionStore {

    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(String sessionId) {
        return store.getOrDefault(sessionId, new ArrayList<>()).stream()
                .filter(m -> !"summary".equals(m.getMsgType()))
                .toList();
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public List<ChatMessage> getNormalMessages(String sessionId) {
        return store.getOrDefault(sessionId, new ArrayList<>()).stream()
                .filter(m -> "normal".equals(m.getMsgType()))
                .toList();
    }

    @Override
    public String getLatestSummary(String sessionId) {
        List<ChatMessage> all = store.getOrDefault(sessionId, new ArrayList<>());
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("summary".equals(all.get(i).getMsgType())) {
                return all.get(i).getContent();
            }
        }
        return null;
    }

    @Override
    public int countNormalChars(String sessionId) {
        return getNormalMessages(sessionId).stream()
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                .sum();
    }

    @Override
    public void replaceSummary(String sessionId, String content) {
        List<ChatMessage> all = store.computeIfAbsent(sessionId, k -> new ArrayList<>());
        all.removeIf(m -> "summary".equals(m.getMsgType()));
        all.add(new ChatMessage("system", content, "summary"));
    }

    @Override
    public void archiveOlder(String sessionId, int keepCount) {
        List<ChatMessage> normals = getNormalMessages(sessionId);
        for (int i = 0; i < normals.size() - keepCount; i++) {
            normals.get(i).setMsgType("archived");
        }
    }
}
