package com.jobagent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTest {

    @Test
    void twoArgConstructorDefaultsToNormal() {
        ChatMessage m = new ChatMessage("user", "你好");
        assertEquals("user", m.getRole());
        assertEquals("你好", m.getContent());
        assertEquals("normal", m.getMsgType());
    }

    @Test
    void threeArgConstructorSetsType() {
        ChatMessage m = new ChatMessage("system", "摘要内容", "summary");
        assertEquals("summary", m.getMsgType());
    }

    @Test
    void setterChangesType() {
        ChatMessage m = new ChatMessage("user", "hi");
        m.setMsgType("archived");
        assertEquals("archived", m.getMsgType());
    }
}
