package com.jobagent.session;

import com.jobagent.llm.ChatMessage;

import java.util.List;

public interface SessionStore {
    List<ChatMessage> getMessages(String sessionId);
    void append(String sessionId, ChatMessage message);
}
