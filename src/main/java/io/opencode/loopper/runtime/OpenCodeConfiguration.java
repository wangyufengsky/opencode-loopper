package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class OpenCodeConfiguration {
    @Bean
    OpenCodeRuntimeManager openCodeRuntimeManager(LoopperProperties properties) {
        return new OpenCodeRuntimeManager(properties);
    }

    @Bean
    OpenCodeCapabilityRegistry openCodeCapabilityRegistry() {
        return new OpenCodeCapabilityRegistry();
    }

    @Bean
    OpenCodeClient openCodeClient(LoopperProperties properties, OpenCodeRuntimeManager runtimeManager,
                                  OpenCodeCapabilityRegistry capabilities) {
        if ("fake".equalsIgnoreCase(properties.getOpenCode().getMode())) return new FakeOpenCodeClient();
        return new HttpOpenCodeClient(RestClient.builder(), runtimeManager::connectionForClient, properties, capabilities);
    }
}
