package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlSessionStoreTest {

    @Test
    void getMessagesReturnsOrderedHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<ChatMessage> rows = List.of(
                new ChatMessage("user", "你好"),
                new ChatMessage("assistant", "你好，我是求职助手"));
        when(jdbc.query(
                eq("SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY id"),
                ArgumentMatchers.<RowMapper<ChatMessage>>any(),
                eq("s1")))
                .thenReturn(rows);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        List<ChatMessage> result = store.getMessages("s1");

        assertEquals(2, result.size());
        assertEquals("user", result.get(0).getRole());
        assertEquals("你好", result.get(0).getContent());
    }

    @Test
    void appendUpsertsSessionThenInsertsMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MysqlSessionStore store = new MysqlSessionStore(jdbc);

        store.append("s1", new ChatMessage("user", "帮我制定学习计划"));

        verify(jdbc).update(
                eq("INSERT INTO chat_session (session_id) VALUES (?) ON DUPLICATE KEY UPDATE updated_at = NOW()"),
                eq("s1"));
        verify(jdbc).update(
                eq("INSERT INTO chat_message (session_id, role, content) VALUES (?, ?, ?)"),
                eq("s1"), eq("user"), eq("帮我制定学习计划"));
    }
}
