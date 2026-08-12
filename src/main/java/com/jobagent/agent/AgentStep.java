package com.jobagent.agent;

import lombok.Data;

@Data
public class AgentStep {
    private int index;
    private String thinking;
    private String tool;
    private String params;
    private String result;
}
