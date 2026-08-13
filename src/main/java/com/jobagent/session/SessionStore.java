package com.jobagent.session;

import com.jobagent.llm.ChatMessage;

import java.util.List;

public interface SessionStore {
    List<ChatMessage> getMessages(String sessionId);
    void append(String sessionId, ChatMessage message);

    List<ChatMessage> getNormalMessages(String sessionId);
    String getLatestSummary(String sessionId);
    int countNormalChars(String sessionId);
    void replaceSummary(String sessionId, String content);
    void archiveOlder(String sessionId, int keepCount);
}
