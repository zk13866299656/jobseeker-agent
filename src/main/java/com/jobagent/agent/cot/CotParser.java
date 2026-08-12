package com.jobagent.agent.cot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
                return CotResult.toolCall(thinking, tool, parseParams(node.get("params")));
            }
            throw new BizException("CoT 输出缺少 tool 或 final_answer 字段");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("CoT JSON 解析失败: " + e.getMessage());
        }
    }

    private Map<String, Object> parseParams(JsonNode paramsNode) {
        if (paramsNode == null || paramsNode.isMissingNode() || paramsNode.isNull()) {
            return new HashMap<>();
        }
        return objectMapper.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {});
    }

    private String extractJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
