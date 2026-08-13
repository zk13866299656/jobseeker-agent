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
            "SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type <> 'summary' ORDER BY id";
    private static final String SELECT_NORMAL =
            "SELECT role, content FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id";
    private static final String SELECT_LATEST_SUMMARY =
            "SELECT content FROM chat_message WHERE session_id = ? AND msg_type = 'summary' ORDER BY id DESC LIMIT 1";
    private static final String COUNT_NORMAL_CHARS =
            "SELECT COALESCE(SUM(CHAR_LENGTH(content)), 0) FROM chat_message WHERE session_id = ? AND msg_type = 'normal'";
    private static final String DELETE_SUMMARY =
            "DELETE FROM chat_message WHERE session_id = ? AND msg_type = 'summary'";
    private static final String INSERT_SUMMARY =
            "INSERT INTO chat_message (session_id, role, content, msg_type) VALUES (?, 'system', ?, 'summary')";
    private static final String SELECT_NORMAL_IDS =
            "SELECT id FROM chat_message WHERE session_id = ? AND msg_type = 'normal' ORDER BY id";
    private static final String ARCHIVE_OLDER =
            "UPDATE chat_message SET msg_type = 'archived' WHERE session_id = ? AND msg_type = 'normal' AND id < ?";
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

    @Override
    public List<ChatMessage> getNormalMessages(String sessionId) {
        return jdbcTemplate.query(SELECT_NORMAL,
                (rs, rowNum) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                sessionId);
    }

    @Override
    public String getLatestSummary(String sessionId) {
        List<String> result = jdbcTemplate.query(SELECT_LATEST_SUMMARY,
                (rs, rowNum) -> rs.getString("content"), sessionId);
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public int countNormalChars(String sessionId) {
        Long n = jdbcTemplate.queryForObject(COUNT_NORMAL_CHARS, Long.class, sessionId);
        return n == null ? 0 : n.intValue();
    }

    @Override
    public void replaceSummary(String sessionId, String content) {
        jdbcTemplate.update(DELETE_SUMMARY, sessionId);
        jdbcTemplate.update(INSERT_SUMMARY, sessionId, content);
    }

    @Override
    public void archiveOlder(String sessionId, int keepCount) {
        List<Long> ids = jdbcTemplate.query(SELECT_NORMAL_IDS,
                (rs, rowNum) -> rs.getLong("id"), sessionId);
        if (ids.size() <= keepCount) {
            return;
        }
        long keepFromId = ids.get(ids.size() - keepCount);
        jdbcTemplate.update(ARCHIVE_OLDER, sessionId, keepFromId);
    }
}
