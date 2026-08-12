-- V2: 给 user_memory 加唯一键 (user_id, memory_type)，支持同类记忆 upsert 覆盖
ALTER TABLE user_memory
    ADD UNIQUE KEY uk_user_memory_type (user_id, memory_type);
