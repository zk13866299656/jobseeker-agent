package com.jobagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractor {

    private static final String EXTRACTOR_SYSTEM = """
            你是一个记忆抽取器。从对话中抽取关于用户的稳定事实，用于跨会话记忆。
            只输出一个 JSON 数组，每个元素是 {"type": "...", "content": "..."}。
            type 只能是以下之一：name（称呼）、target_role（目标岗位）、skill（技能）、progress（求职进度）、preference（偏好）、fact（其他稳定事实）。
            要求：
            - 只抽取稳定、可长期复用的信息（称呼、目标、技能、进度、偏好等），不抽取临时请求。
            - 若本次对话没有新的稳定信息，输出空数组 []。
            - 若用户纠正了之前的说法，用新说法覆盖旧说法。
            - 不要输出 JSON 以外的任何文字。
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public List<UserMemory> extract(List<UserMemory> existing, String userMessage, String assistantAnswer) {
        String raw = llmClient.chat(List.of(
                new ChatMessage("system", EXTRACTOR_SYSTEM),
                new ChatMessage("user", buildPrompt(existing, userMessage, assistantAnswer))));
        return parse(raw);
    }

    List<UserMemory> parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            if (!root.isArray()) {
                return List.of();
            }
            List<UserMemory> result = new ArrayList<>();
            for (JsonNode node : root) {
                String type = node.path("type").asText("");
                String content = node.path("content").asText("");
                if (!type.isBlank() && !content.isBlank()) {
                    result.add(new UserMemory(type, content));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆抽取解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(List<UserMemory> existing, String userMessage, String assistantAnswer) {
        StringBuilder sb = new StringBuilder();
        sb.append("已有记忆：\n");
        if (existing == null || existing.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (UserMemory m : existing) {
                sb.append("- [").append(m.getType()).append("] ").append(m.getContent()).append("\n");
            }
        }
        sb.append("\n最新一轮对话：\n");
        sb.append("用户：").append(userMessage).append("\n");
        sb.append("助手：").append(assistantAnswer).append("\n");
        return sb.toString();
    }

    private String extractJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end >= start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
