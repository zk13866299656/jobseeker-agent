package com.jobagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    @Test
    void loadReturnsEmptyWhenUserNotExist() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.findUserId("u1")).thenReturn(null);
        MemoryService service = new MemoryService(store, extractor);
        assertTrue(service.load("u1").isEmpty());
        verify(store, never()).load(anyLong());
    }

    @Test
    void loadReturnsMemories() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.findUserId("u1")).thenReturn(5L);
        when(store.load(5L)).thenReturn(List.of(new UserMemory("name", "张三")));
        MemoryService service = new MemoryService(store, extractor);
        List<UserMemory> result = service.load("u1");
        assertEquals(1, result.size());
        assertEquals("张三", result.get(0).getContent());
    }

    @Test
    void loadSwallowsException() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.findUserId("u1")).thenThrow(new RuntimeException("db down"));
        MemoryService service = new MemoryService(store, extractor);
        assertTrue(service.load("u1").isEmpty());
    }

    @Test
    void extractAndStoreOrchestrates() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.getOrCreateUser("u1")).thenReturn(5L);
        when(store.load(5L)).thenReturn(List.of(new UserMemory("name", "张三")));
        when(extractor.extract(anyList(), eq("我叫李四"), eq("你好李四")))
                .thenReturn(List.of(new UserMemory("name", "李四"), new UserMemory("target_role", "Java后端")));
        MemoryService service = new MemoryService(store, extractor);
        service.extractAndStore("u1", "s1", "我叫李四", "你好李四");
        verify(store).upsert(5L, "name", "李四", "s1");
        verify(store).upsert(5L, "target_role", "Java后端", "s1");
    }

    @Test
    void extractAndStoreSwallowsException() {
        UserMemoryStore store = mock(UserMemoryStore.class);
        MemoryExtractor extractor = mock(MemoryExtractor.class);
        when(store.getOrCreateUser("u1")).thenThrow(new RuntimeException("db down"));
        MemoryService service = new MemoryService(store, extractor);
        service.extractAndStore("u1", "s1", "x", "y");
        verify(store, never()).upsert(anyLong(), anyString(), anyString(), anyString());
    }
}
