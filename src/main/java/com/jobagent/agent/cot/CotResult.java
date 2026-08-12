package com.jobagent.agent.cot;

import lombok.Data;

import java.util.Map;

@Data
public class CotResult {

    public enum Type { TOOL_CALL, FINAL_ANSWER }

    private Type type;
    private String thinking;
    private String tool;
    private Map<String, Object> params;
    private String finalAnswer;

    public static CotResult toolCall(String thinking, String tool, Map<String, Object> params) {
        CotResult r = new CotResult();
        r.type = Type.TOOL_CALL;
        r.thinking = thinking;
        r.tool = tool;
        r.params = params;
        return r;
    }

    public static CotResult finalAnswer(String thinking, String finalAnswer) {
        CotResult r = new CotResult();
        r.type = Type.FINAL_ANSWER;
        r.thinking = thinking;
        r.finalAnswer = finalAnswer;
        return r;
    }
}
