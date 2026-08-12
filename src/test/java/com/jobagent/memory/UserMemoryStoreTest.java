package com.jobagent.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryStoreTest {

    private static final String SELECT_USER_ID = "SELECT id FROM app_user WHERE username = ?";
    private static final String INSERT_USER = "INSERT INTO app_user (username) VALUES (?)";
    private static final String SELECT_MEMORIES = "SELECT memory_type, content FROM user_memory WHERE user_id = ? ORDER BY id";
    private static final String UPSERT_MEMORY =
            "INSERT INTO user_memory (user_id, memory_type, content, source_session_id) VALUES (?, ?, ?, ?) AS new " +
            "ON DUPLICATE KEY UPDATE content = new.content, source_session_id = new.source_session_id, updated_at = NOW()";

    @Test
    void findUserIdReturnsNullWhenNoRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of());
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertNull(store.findUserId("u1"));
    }

    @Test
    void findUserIdReturnsIdWhenExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of(5L));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertEquals(5L, store.findUserId("u1"));
    }

    @Test
    void getOrCreateUserReturnsExistingWithoutInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of(5L));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertEquals(5L, store.getOrCreateUser("u1"));
        verify(jdbc, never()).update(eq(INSERT_USER), eq("u1"));
    }

    @Test
    void getOrCreateUserInsertsWhenMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_USER_ID), ArgumentMatchers.<RowMapper<Long>>any(), eq("u1")))
                .thenReturn(List.of(), List.of(6L));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        assertEquals(6L, store.getOrCreateUser("u1"));
        verify(jdbc).update(eq(INSERT_USER), eq("u1"));
    }

    @Test
    void loadMapsRowsToMemories() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(SELECT_MEMORIES), ArgumentMatchers.<RowMapper<UserMemory>>any(), eq(5L)))
                .thenReturn(List.of(new UserMemory("name", "张三"), new UserMemory("target_role", "Java后端")));
        UserMemoryStore store = new UserMemoryStore(jdbc);
        List<UserMemory> result = store.load(5L);
        assertEquals(2, result.size());
        assertEquals("name", result.get(0).getType());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void upsertCallsUpdateWithAllArgs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UserMemoryStore store = new UserMemoryStore(jdbc);
        store.upsert(5L, "name", "张三", "s1");
        verify(jdbc).update(eq(UPSERT_MEMORY), eq(5L), eq("name"), eq("张三"), eq("s1"));
    }
}
