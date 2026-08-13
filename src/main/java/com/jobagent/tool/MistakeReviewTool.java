package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MistakeReviewTool implements Tool {

    private final MemoryService memoryService;

    @Override
    public String name() {
        return "mistake_review";
    }

    @Override
    public String description() {
        return "针对用户答错的面试题生成结构化复盘框架";
    }

    @Override
    public String parametersSchema() {
        return "{\"question\":\"题目\", \"myAnswer\":\"可选，用户当时的错误回答\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        List<UserMemory> memories = memoryService.load(userId);
        String skill = memoryValue(memories, "skill");
        String targetRole = memoryValue(memories, "target_role");
        String question = params.get("question") == null ? "（未提供题目）" : String.valueOf(params.get("question"));
        String myAnswer = params.get("myAnswer") == null ? null : String.valueOf(params.get("myAnswer"));

        StringBuilder sb = new StringBuilder();
        sb.append("错题复盘卡：\n\n");
        sb.append("1. 题目：").append(question).append("\n");
        if (myAnswer != null && !myAnswer.isBlank()) {
            sb.append("2. 我的错误回答：").append(myAnswer).append("\n");
        } else {
            sb.append("2. 我的错误回答：（未提供）\n");
        }
        sb.append("3. 正确思路：（请结合下面的知识点，讲清正确解法）\n");
        if (skill != null) {
            sb.append("   - 关联你的技能盲区：").append(skill).append("\n");
        }
        if (targetRole != null) {
            sb.append("   - 该题与目标岗位 ").append(targetRole).append(" 的关联：\n");
        }
        sb.append("4. 知识盲区：请指出这道题涉及的考点，以及你哪里理解错了\n");
        sb.append("5. 下次怎么做：给出 1-2 条可执行的巩固动作（如重刷该知识点、做 2 道同类题）\n");
        return sb.toString();
    }

    private String memoryValue(List<UserMemory> memories, String type) {
        return memories.stream()
                .filter(m -> type.equals(m.getType()))
                .map(UserMemory::getContent)
                .findFirst().orElse(null);
    }
}
