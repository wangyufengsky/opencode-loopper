package io.opencode.loopper.api;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the six annotated Loopper methods with Spring AI's official MCP Server starter. */
@Configuration(proxyBeanMethods = false)
public class SpringAiMcpServerConfiguration {
    @Bean
    ToolCallbackProvider loopperMcpToolCallbackProvider(LoopperMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
