package com.jobagent.llm;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChatMessage {
    private String role;    // system / user / assistant
    private String content;
    private String msgType = "normal";  // normal / summary / archived

    public ChatMessage(String role, String content) {
        this(role, content, "normal");
    }

    public ChatMessage(String role, String content, String msgType) {
        this.role = role;
        this.content = content;
        this.msgType = msgType;
    }
}
