package com.jobagent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InterviewQuestionTool implements Tool {

    private static final Map<String, String> QUESTIONS = new LinkedHashMap<>();

    static {
        QUESTIONS.put("Java基础", "1. HashMap 底层原理？ 2. JVM 内存模型？ 3. == 与 equals 区别？");
        QUESTIONS.put("SpringBoot", "1. 自动装配原理？ 2. Bean 生命周期？ 3. AOP 实现原理？");
        QUESTIONS.put("Redis", "1. 缓存穿透/击穿/雪崩及解决方案？ 2. RDB 与 AOF 持久化？ 3. 分布式锁实现？");
    }

    @Override
    public String name() {
        return "interview_question";
    }

    @Override
    public String description() {
        return "根据知识点返回 Java/SpringBoot/Redis 高频面试题";
    }

    @Override
    public String parametersSchema() {
        return "{\"topic\":\"知识点，如 Java基础/SpringBoot/Redis\"}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String topic = String.valueOf(params.getOrDefault("topic", "Java基础"));
        String q = QUESTIONS.get(topic);
        if (q == null) {
            return "暂未收录「" + topic + "」的面试题，可选题：Java基础、SpringBoot、Redis";
        }
        return "「" + topic + "」高频面试题：\n" + q;
    }
}
