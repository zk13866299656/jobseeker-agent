package com.jobagent.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryStore store;
    private final MemoryExtractor extractor;

    public List<UserMemory> load(String userId) {
        try {
            Long appUserId = store.findUserId(userId);
            if (appUserId == null) {
                return List.of();
            }
            return store.load(appUserId);
        } catch (Exception e) {
            log.warn("记忆加载失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void extractAndStore(String userId, String sessionId, String userMessage, String assistantAnswer) {
        try {
            long appUserId = store.getOrCreateUser(userId);
            List<UserMemory> existing = store.load(appUserId);
            List<UserMemory> extracted = extractor.extract(existing, userMessage, assistantAnswer);
            for (UserMemory m : extracted) {
                store.upsert(appUserId, m.getType(), m.getContent(), sessionId);
            }
        } catch (Exception e) {
            log.warn("记忆抽取失败（不影响回答）: {}", e.getMessage());
        }
    }
}
