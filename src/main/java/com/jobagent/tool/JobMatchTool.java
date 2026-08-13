package com.jobagent.tool;

import com.jobagent.memory.MemoryService;
import com.jobagent.memory.UserMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JobMatchTool implements Tool {

    private static final Map<String, List<String>> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put("Java后端", List.of("Java", "SpringBoot", "MySQL", "Redis", "并发/JVM", "网络"));
        ROLES.put("前端", List.of("JavaScript", "Vue/React", "HTML/CSS", "HTTP/网络", "浏览器"));
        ROLES.put("测试开发", List.of("Java/Python", "测试理论", "自动化测试", "SQL", "Linux"));
        ROLES.put("大数据", List.of("Java/Scala", "Hadoop", "Spark", "SQL", "分布式"));
        ROLES.put("运维/DevOps", List.of("Linux", "Docker", "K8s", "网络", "Shell/Python", "监控"));
        ROLES.put("客户端", List.of("Java/Kotlin", "移动框架", "网络", "数据结构"));
        ROLES.put("算法", List.of("Python/C++", "数据结构与算法", "机器学习", "数学"));
    }

    private final MemoryService memoryService;

    @Override
    public String name() {
        return "job_match";
    }

    @Override
    public String description() {
        return "根据用户目标岗位和技能，匹配软工应届生岗位方向并指出技能差距";
    }

    @Override
    public String parametersSchema() {
        return "{\"role\":\"可选，指定方向如 后端/前端/测试/大数据，不传则用记忆中的目标岗位\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        List<UserMemory> memories = memoryService.load(userId);
        String targetRole = memoryValue(memories, "target_role");
        List<String> skills = splitSkills(memoryValue(memories, "skill"));

        String role = params.get("role") == null ? normalizeRole(targetRole) : String.valueOf(params.get("role"));

        StringBuilder sb = new StringBuilder();
        sb.append("岗位匹配结果：\n\n");

        if (role != null && ROLES.containsKey(role)) {
            sb.append("你的目标方向：").append(role).append("\n");
            sb.append("该方向所需技能：").append(String.join("、", ROLES.get(role))).append("\n");
            List<String> missing = missingSkills(role, skills);
            if (missing.isEmpty()) {
                sb.append("你已掌握该方向全部核心技能，很棒！\n\n");
            } else {
                sb.append("你目前还缺：").append(String.join("、", missing)).append("\n\n");
            }
        }

        sb.append("按你的技能匹配到的岗位方向（匹配度从高到低）：\n");
        for (Map.Entry<String, List<String>> e : ROLES.entrySet()) {
            String r = e.getKey();
            List<String> required = e.getValue();
            long hit = required.stream().filter(skills::contains).count();
            int pct = required.isEmpty() ? 0 : (int) (hit * 100 / required.size());
            sb.append("- ").append(r).append("：匹配度 ").append(pct).append("%");
            List<String> miss = missingSkills(r, skills);
            if (!miss.isEmpty()) {
                sb.append("（缺：").append(String.join("、", miss)).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n建议：优先补齐目标方向缺失的核心技能，具体学习顺序可结合 study_plan 工具制定计划。");
        return sb.toString();
    }

    private String memoryValue(List<UserMemory> memories, String type) {
        return memories.stream()
                .filter(m -> type.equals(m.getType()))
                .map(UserMemory::getContent)
                .findFirst().orElse(null);
    }

    private List<String> splitSkills(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，、;；]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String normalizeRole(String targetRole) {
        if (targetRole == null) {
            return null;
        }
        for (String r : ROLES.keySet()) {
            if (targetRole.contains(r)) {
                return r;
            }
        }
        return null;
    }

    private List<String> missingSkills(String role, List<String> skills) {
        return ROLES.get(role).stream().filter(s -> !skills.contains(s)).toList();
    }
}
