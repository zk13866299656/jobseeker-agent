package com.jobagent.agent;

import com.jobagent.agent.cot.CotParser;
import com.jobagent.agent.cot.CotPromptBuilder;
import com.jobagent.agent.cot.CotResult;
import com.jobagent.common.BizException;
import com.jobagent.llm.ChatMessage;
import com.jobagent.llm.LlmClient;
import com.jobagent.session.SessionStore;
import com.jobagent.tool.Tool;
import com.jobagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final int MAX_STEPS = 10;

    private final LlmClient llmClient;
    private final CotParser cotParser;
    private final CotPromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final SessionStore sessionStore;

    public AgentResult run(String sessionId, String userInput, Consumer<AgentEvent> eventSink) {
        List<ChatMessage> history = sessionStore.getMessages(sessionId);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", promptBuilder.build()));
        messages.addAll(history);
        messages.add(new ChatMessage("user", userInput));

        List<AgentStep> steps = new ArrayList<>();
        String finalAnswer = null;

        int stepNo = 0;
        while (stepNo < MAX_STEPS) {
            stepNo++;

            String raw = llmClient.chat(messages);
            messages.add(new ChatMessage("assistant", raw));

            CotResult cot;
            try {
                cot = cotParser.parse(raw);
            } catch (BizException e) {
                log.warn("CoT 解析失败: {}", e.getMessage());
                messages.add(new ChatMessage("user", "你的输出不是合法 JSON，请只输出一个 JSON 对象（含 thinking 字段，以及 tool+params 或 final_answer 之一）"));
                continue;
            }
            emit(eventSink, "thinking", cot.getThinking());

            if (cot.getType() == CotResult.Type.FINAL_ANSWER) {
                finalAnswer = cot.getFinalAnswer();
                break;
            }

            Tool tool = toolRegistry.find(cot.getTool());
            if (tool == null) {
                messages.add(new ChatMessage("user", "未知工具: " + cot.getTool() + "，请重新选择可用工具"));
                continue;
            }

            emit(eventSink, "tool_call", tool.name());
            String toolResult;
            try {
                toolResult = tool.execute(cot.getParams());
            } catch (Exception e) {
                log.warn("工具执行异常: {}", e.getMessage());
                toolResult = "工具执行失败：" + e.getMessage();
            }
            emit(eventSink, "tool_result", toolResult);

            AgentStep step = new AgentStep();
            step.setIndex(stepNo);
            step.setThinking(cot.getThinking());
            step.setTool(tool.name());
            step.setParams(String.valueOf(cot.getParams()));
            step.setResult(toolResult);
            steps.add(step);

            messages.add(new ChatMessage("user", "工具 " + tool.name() + " 执行结果：\n" + toolResult));
        }

        if (finalAnswer == null) {
            finalAnswer = "已达到最大执行步数，未能完成。已执行步骤见日志。";
        }

        sessionStore.append(sessionId, new ChatMessage("user", userInput));
        sessionStore.append(sessionId, new ChatMessage("assistant", finalAnswer));

        AgentResult result = new AgentResult();
        result.setFinalAnswer(finalAnswer);
        result.setSteps(steps);
        return result;
    }

    private void emit(Consumer<AgentEvent> sink, String type, String content) {
        if (sink != null) {
            sink.accept(new AgentEvent(type, content));
        }
    }
}
