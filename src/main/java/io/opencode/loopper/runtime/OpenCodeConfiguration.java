package io.opencode.loopper.runtime;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.service.StoryAccountingCoordinator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration
class OpenCodeConfiguration {
    @Bean
    InternalMcpRuntimeAccess internalMcpRuntimeAccess() {
        return new InternalMcpRuntimeAccess();
    }

    @Bean
    OpenCodeAttachmentResources openCodeAttachmentResources(InternalMcpRuntimeAccess access) {
        return new OpenCodeAttachmentResources(access);
    }

    @Bean
    InternalMcpCredentialProvider internalMcpCredentialProvider(Environment environment) {
        return new InternalMcpCredentialProvider(() -> boundHttpPort(environment));
    }

    static int boundHttpPort(Environment environment) {
        Integer local = environment.getProperty("local.server.port", Integer.class);
        if (local != null) return local;
        return environment.getProperty("server.port", Integer.class, 8080);
    }

    @Bean
    OpenCodeRuntimeManager openCodeRuntimeManager(LoopperProperties properties,
                                                  InternalMcpCredentialProvider credentials,
                                                  InternalMcpRuntimeAccess access, OwnedRuntimeGenerationStore generations) {
        var manager = new OpenCodeRuntimeManager(properties, credentials, access);
        manager.installGenerations(generations);
        return manager;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> managedOpenCodeStartup(OpenCodeRuntimeManager runtimeManager) {
        return event -> runtimeManager.startManagedOnApplicationReady();
    }

    @Bean
    OpenCodeCapabilityRegistry openCodeCapabilityRegistry() {
        return new OpenCodeCapabilityRegistry();
    }

    @Bean
    OpenCodeClient openCodeClient(LoopperProperties properties, OpenCodeRuntimeManager runtimeManager,
                                  OpenCodeCapabilityRegistry capabilities,
                                  OpenCodeSessionRuntimeBindings runtimeBindings, OpenCodeAttachmentResources resources,
                                  StoryAccountingCoordinator storyAccounting, AssistRuntimeSupport assist, PptRuntimeSupport ppt, ConfiguredRoleRuntime roles, ConfiguredAccountingRole accountingRoles) {
        if ("fake".equalsIgnoreCase(properties.getOpenCode().getMode())) {
            var client = new FakeOpenCodeClient(runtimeBindings); client.installRoles(roles); return client;
        }
        HttpOpenCodeClient client = new HttpOpenCodeClient(RestClient.builder(), runtimeManager::connectionForClient,
                runtimeManager::currentIdentityNoIo, properties, capabilities, runtimeBindings, resources,
                storyAccounting);
        client.installAssist(assist);
        client.installPpt(ppt);
        client.installRoles(roles);
        client.installAccountingRoles(accountingRoles);
        return client;
    }
}
