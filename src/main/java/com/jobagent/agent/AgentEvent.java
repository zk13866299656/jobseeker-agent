package com.jobagent.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentEvent {
    private String type;    // thinking / tool_call / tool_result
    private String content;
}
