package com.jobagent.agent.cot;

import com.jobagent.tool.Tool;
import com.jobagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CotPromptBuilder {

    private final ToolRegistry toolRegistry;

    public String build() {
        String tools = toolRegistry.getAll().stream()
                .map(t -> String.format("- %s: %s；参数：%s",
                        t.name(), t.description(), t.parametersSchema()))
                .collect(Collectors.joining("\n"));

        return """
                你是一个求职规划智能 Agent，帮助软件工程应届生完成求职规划。

                你可以调用以下工具来完成任务：
                %s

                你必须只输出一个 JSON 对象（不要输出 markdown 代码块或其他文字），格式如下：
                1. 需要调用工具时：
                {"thinking": "你的思考过程", "tool": "工具名", "params": {参数}}
                2. 可以直接回答时：
                {"thinking": "你的思考过程", "final_answer": "最终答案"}

                要求：
                - thinking 字段写明你的推理过程。
                - 工具返回结果后，基于结果继续判断是否还需要调用其他工具，直到能给出最终答案。
                """.formatted(tools);
    }
}
