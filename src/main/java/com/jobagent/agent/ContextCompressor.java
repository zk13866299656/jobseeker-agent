package com.jobagent.agent;

import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.SessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    static final int THRESHOLD_CHARS = 8000;
    static final int KEEP_RECENT = 10;

    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是一个对话摘要助手。请把下面的求职规划对话浓缩成一段简洁的中文摘要（不超过300字）。" +
            "只保留：用户的关键事实（称呼、目标岗位、技能、求职进度）、用户偏好、待办事项。" +
            "不要臆造信息，不要添加对话中不存在的内容。";

    private final LlmClient llmClient;
    private final SessionStore sessionStore;

    public void compressIfNeeded(String sessionId) {
        try {
            int chars = sessionStore.countNormalChars(sessionId);
            if (chars <= THRESHOLD_CHARS) {
                return;
            }
            String oldSummary = sessionStore.getLatestSummary(sessionId);
            List<ChatMessage> normals = sessionStore.getNormalMessages(sessionId);
            String newSummary = llmClient.chat(buildSummaryMessages(oldSummary, normals));
            if (newSummary == null || newSummary.isBlank()) {
                return;
            }
            sessionStore.replaceSummary(sessionId, newSummary.trim());
            sessionStore.archiveOlder(sessionId, KEEP_RECENT);
        } catch (Exception e) {
            log.warn("上下文压缩失败（不影响回答）", e);
        }
    }

    List<ChatMessage> buildSummaryMessages(String oldSummary, List<ChatMessage> normals) {
        StringBuilder sb = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("之前的摘要：\n").append(oldSummary).append("\n\n");
        }
        sb.append("最新对话：\n");
        for (ChatMessage m : normals) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        return List.of(
                new ChatMessage("system", SUMMARY_SYSTEM_PROMPT),
                new ChatMessage("user", sb.toString())
        );
    }
}
