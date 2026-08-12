package com.jobagent.session;

import com.jobagent.llm.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MysqlSessionStore implements SessionStore {

    private static final String SELECT_MESSAGES =
            "SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY id";
    private static final String UPSERT_SESSION =
            "INSERT INTO chat_session (session_id) VALUES (?) ON DUPLICATE KEY UPDATE updated_at = NOW()";
    private static final String INSERT_MESSAGE =
            "INSERT INTO chat_message (session_id, role, content) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ChatMessage> getMessages(String sessionId) {
        return jdbcTemplate.query(SELECT_MESSAGES,
                (rs, rowNum) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                sessionId);
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        jdbcTemplate.update(UPSERT_SESSION, sessionId);
        jdbcTemplate.update(INSERT_MESSAGE, sessionId, message.getRole(), message.getContent());
    }
}
