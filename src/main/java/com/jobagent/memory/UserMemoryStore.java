package com.jobagent.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UserMemoryStore {

    private static final String SELECT_USER_ID =
            "SELECT id FROM app_user WHERE username = ?";
    private static final String INSERT_USER =
            "INSERT INTO app_user (username) VALUES (?)";
    private static final String SELECT_MEMORIES =
            "SELECT memory_type, content FROM user_memory WHERE user_id = ? ORDER BY id";
    private static final String UPSERT_MEMORY =
            "INSERT INTO user_memory (user_id, memory_type, content, source_session_id) VALUES (?, ?, ?, ?) AS new " +
            "ON DUPLICATE KEY UPDATE content = new.content, source_session_id = new.source_session_id, updated_at = NOW()";

    private final JdbcTemplate jdbcTemplate;

    public Long findUserId(String userId) {
        List<Long> ids = jdbcTemplate.query(SELECT_USER_ID, (rs, n) -> rs.getLong("id"), userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long getOrCreateUser(String userId) {
        Long id = findUserId(userId);
        if (id != null) {
            return id;
        }
        jdbcTemplate.update(INSERT_USER, userId);
        return findUserId(userId);
    }

    public List<UserMemory> load(long appUserId) {
        return jdbcTemplate.query(SELECT_MEMORIES,
                (rs, n) -> new UserMemory(rs.getString("memory_type"), rs.getString("content")),
                appUserId);
    }

    public void upsert(long appUserId, String type, String content, String sessionId) {
        jdbcTemplate.update(UPSERT_MEMORY, appUserId, type, content, sessionId);
    }
}
