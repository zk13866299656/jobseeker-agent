package com.jobagent.tool;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    String parametersSchema();
    String execute(String userId, Map<String, Object> params);
}
