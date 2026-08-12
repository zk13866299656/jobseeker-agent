package com.jobagent.llm;

import java.util.List;

public interface LlmClient {
    String chat(List<ChatMessage> messages);
}
