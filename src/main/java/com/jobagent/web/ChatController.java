package com.jobagent.web;

import com.jobagent.agent.AgentLoop;
import com.jobagent.agent.AgentResult;
import com.jobagent.llm.ChatMessage;
import com.jobagent.session.SessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final AgentLoop agentLoop;
    private final Executor agentExecutor;
    private final SessionStore sessionStore;

    @GetMapping("/api/chat/history")
    public List<ChatMessage> history(@RequestParam(defaultValue = "default") String sessionId) {
        return sessionStore.getMessages(sessionId);
    }

    @GetMapping("/api/chat/stream")
    public SseEmitter stream(@RequestParam String message,
                             @RequestParam(defaultValue = "default") String sessionId) {
        SseEmitter emitter = new SseEmitter(120_000L);

        agentExecutor.execute(() -> {
            try {
                AgentResult result = agentLoop.run(sessionId, message, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.getType())
                                .data(String.valueOf(event.getContent())));
                    } catch (Exception ignored) {
                    }
                });
                emitter.send(SseEmitter.event().name("final_answer").data(result.getFinalAnswer()));
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                log.error("Agent 执行异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }
}
