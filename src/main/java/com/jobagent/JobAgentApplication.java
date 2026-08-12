package com.jobagent;

import com.jobagent.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class JobAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobAgentApplication.class, args);
    }
}
