package com.jobagent.agent;

import lombok.Data;

import java.util.List;

@Data
public class AgentResult {
    private String finalAnswer;
    private List<AgentStep> steps;
}
