package com.jobagent.tool;

import java.util.Map;

public interface Tool {
    String name()
            ;
    String description();
    String parametersSchema();
    String execute(Map<String, Object> params);
}
