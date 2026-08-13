package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResumeImproveTool implements Tool {

    private final MemoryService memoryService;

    @Override
    public String name() {
        return "resume_improve";
    }

    @Override
    public String description() {
        return "基于用户技能/进度/目标岗位，用 STAR 法则生成简历要点或改写经历";
    }

    @Override
    public String parametersSchema() {
        return "{\"experience\":\"可选，用户补充的一段项目/实习经历描述\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        List<UserMemory> memories = memoryService.load(userId);
        String skill = memoryValue(memories, "skill");
        String targetRole = memoryValue(memories, "target_role");
        String progress = memoryValue(memories, "progress");
        String experience = params.get("experience") == null ? null : String.valueOf(params.get("experience"));

        StringBuilder sb = new StringBuilder();
        sb.append("简历优化建议（STAR 法则）：\n\n");

        if (experience != null && !experience.isBlank()) {
            sb.append("针对你的这段经历：").append(experience).append("\n");
            sb.append("建议改写为：\n");
            sb.append("- 情境 Situation：一句话交代项目背景与目标\n");
            sb.append("- 任务 Task：你在其中承担的角色与责任\n");
            sb.append("- 行动 Action：你具体做了什么（技术栈/方法）\n");
            sb.append("- 结果 Result：用数据量化产出（性能、效率、规模）\n\n");
        } else {
            sb.append("你当前的画像：\n");
            if (targetRole != null) sb.append("- 目标岗位：").append(targetRole).append("\n");
            if (skill != null) sb.append("- 技能：").append(skill).append("\n");
            if (progress != null) sb.append("- 进度：").append(progress).append("\n");
            sb.append("\n请按 STAR 法则组织你的项目经历，每段经历至少补一个可量化结果。\n");
        }

        sb.append("量化建议：避免「会 XX」「熟悉 XX」这类空泛表达，改成「独立完成 XX 模块，接口 QPS 提升 Y%」这类带数字的说法。");
        return sb.toString();
    }

    private String memoryValue(List<UserMemory> memories, String type) {
        return memories.stream()
                .filter(m -> type.equals(m.getType()))
                .map(UserMemory::getContent)
                .findFirst().orElse(null);
    }
}
