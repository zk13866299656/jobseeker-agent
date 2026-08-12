package com.jobagent.llm;

import com.jobagent.common.BizException;
import com.jobagent.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient implements LlmClient {

    private final LlmProperties llmProperties;
    private final RestTemplate restTemplate;

    @Override
    public String chat(List<ChatMessage> messages) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return doChat(messages);
            } catch (Exception e) {
                last = e;
                log.warn("LLM 调用失败（第 {} 次）: {}", attempt, e.getMessage());
            }
        }
        throw new BizException("大模型调用失败: " + last.getMessage());
    }

    private String doChat(List<ChatMessage> messages) {
        String url = llmProperties.getBaseUrl() + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", llmProperties.getModel());
        body.put("messages", messages);
        body.put("temperature", llmProperties.getTemperature());
        body.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(llmProperties.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<String, Object> resp = restTemplate.postForObject(url, entity, Map.class);

        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
