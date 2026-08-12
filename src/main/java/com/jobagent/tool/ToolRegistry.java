package com.jobagent.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        for (Tool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public Tool find(String name) {
        return tools.get(name);
    }

    public Collection<Tool> getAll() {
        return tools.values();
    }
}
