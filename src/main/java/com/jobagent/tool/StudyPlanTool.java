package com.jobagent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StudyPlanTool implements Tool {

    @Override
    public String name() {
        return "study_plan";
    }

    @Override
    public String description() {
        return "根据目标岗位、薄弱点和剩余天数生成分阶段学习计划";
    }

    @Override
    public String parametersSchema() {
        return "{\"targetJob\":\"目标岗位，如 Java后端开发\", \"weakPoints\":\"薄弱点列表\", \"days\":\"剩余天数\"}";
    }

    @Override
    public String execute(String userId, Map<String, Object> params) {
        String targetJob = String.valueOf(params.getOrDefault("targetJob", "Java后端开发"));
        int days = Integer.parseInt(String.valueOf(params.getOrDefault("days", "30")));
        Object weak = params.get("weakPoints");
        String weakPoints = weak == null ? "Java基础、SpringBoot" : String.valueOf(weak);

        int phase = days / 3;

        StringBuilder sb = new StringBuilder();
        sb.append("为你生成 ").append(days).append(" 天 ").append(targetJob).append(" 学习计划：\n\n");

        sb.append("【第一阶段 基础夯实】第 1-").append(phase).append(" 天：\n");
        sb.append("  - 复习 Java 核心（集合、并发、JVM、异常）\n");
        sb.append("  - 每天 2 小时刷 LeetCode 简单题\n\n");

        sb.append("【第二阶段 框架进阶】第 ").append(phase + 1).append("-").append(phase * 2).append(" 天：\n");
        sb.append("  - SpringBoot 自动装配、AOP、事务\n");
        sb.append("  - MySQL 索引、事务；Redis 缓存\n\n");

        sb.append("【第三阶段 项目与冲刺】第 ").append(phase * 2 + 1).append("-").append(days).append(" 天：\n");
        sb.append("  - 完善 Agent 项目，梳理项目亮点\n");
        sb.append("  - 针对薄弱点：").append(weakPoints).append(" 专项补强\n");
        sb.append("  - 模拟面试 + 高频面试题\n");

        return sb.toString();
    }
}
