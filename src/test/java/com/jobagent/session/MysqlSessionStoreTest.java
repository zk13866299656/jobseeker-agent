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
                eq("SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type <> 'summary' ORDER BY id"),
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

    @Test
    void getMessagesFiltersSummary() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<ChatMessage> rows = List.of(new ChatMessage("user", "你好"));
        when(jdbc.query(
                eq("SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type <> 'summary' ORDER BY id"),
                ArgumentMatchers.<RowMapper<ChatMessage>>any(),
                eq("s1")))
                .thenReturn(rows);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals(1, store.getMessages("s1").size());
    }

    @Test
    void getNormalMessagesFiltersNormalOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<ChatMessage> rows = List.of(new ChatMessage("user", "hi"));
        when(jdbc.query(
                eq("SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id"),
                ArgumentMatchers.<RowMapper<ChatMessage>>any(),
                eq("s1")))
                .thenReturn(rows);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals(1, store.getNormalMessages("s1").size());
    }

    @Test
    void getLatestSummaryReturnsContentOrNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1"),
                ArgumentMatchers.<RowMapper<String>>any(),
                eq("s1")))
                .thenReturn(List.of("摘要内容"));
        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals("摘要内容", store.getLatestSummary("s1"));

        when(jdbc.query(
                eq("SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1"),
                ArgumentMatchers.<RowMapper<String>>any(),
                eq("s2")))
                .thenReturn(List.of());
        assertNull(store.getLatestSummary("s2"));
    }

    @Test
    void countNormalCharsQueriesSum() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                eq("SELECT COALESCE(SUM(CHAR_LENGTH(content)), 0) FROM chat_message WHERE session_id = ? AND msg_type = 'normal'"),
                eq(Long.class), eq("s1")))
                .thenReturn(5000L);

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        assertEquals(5000, store.countNormalChars("s1"));
    }

    @Test
    void replaceSummaryDeletesThenInserts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MysqlSessionStore store = new MysqlSessionStore(jdbc);

        store.replaceSummary("s1", "新摘要");

        verify(jdbc).update(
                eq("DELETE FROM chat_message WHERE session_id = ? AND msg_type = 'summary'"),
                eq("s1"));
        verify(jdbc).update(
                eq("INSERT INTO chat_message (session_id, role, content, msg_type) VALUES (?, 'system', ?, 'summary')"),
                eq("s1"), eq("新摘要"));
    }

    @Test
    void archiveOlderKeepsRecentById() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT id FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id"),
                ArgumentMatchers.<RowMapper<Long>>any(),
                eq("s1")))
                .thenReturn(List.of(1L, 2L, 3L, 4L));

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        store.archiveOlder("s1", 2);

        verify(jdbc).update(
                eq("UPDATE chat_message SET msg_type = 'archived' WHERE session_id = ? AND msg_type = 'normal' AND id < ?"),
                eq("s1"), eq(3L));
    }

    @Test
    void archiveOlderNoOpWhenFewerThanKeep() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT id FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id"),
                ArgumentMatchers.<RowMapper<Long>>any(),
                eq("s1")))
                .thenReturn(List.of(1L, 2L));

        MysqlSessionStore store = new MysqlSessionStore(jdbc);
        store.archiveOlder("s1", 5);

        verify(jdbc, never()).update(anyString(), any(), any());
    }
}
