package com.jobagent.agent.cot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CotParser {

    private final ObjectMapper objectMapper;

    public CotResult parse(String raw) {
        String json = extractJson(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String thinking = node.path("thinking").asText("");

            if (node.has("final_answer")) {
                return CotResult.finalAnswer(thinking, node.path("final_answer").asText());
            }
            if (node.has("tool")) {
                String tool = node.path("tool").asText();
                JsonNode paramsNode = node.path("params");
                Map<String, Object> params = objectMapper.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {});
                return CotResult.toolCall(thinking, tool, params);
            }
            throw new BizException("CoT 输出缺少 tool 或 final_answer 字段");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("CoT JSON 解析失败: " + e.getMessage());
        }
    }

    private String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        return s;
    }
}
