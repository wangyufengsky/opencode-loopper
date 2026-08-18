package io.opencode.loopper.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class ReadResponseConfiguration {
    /** Inbox is a small stable projection; a conditional GET avoids re-serializing unchanged pending items. */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> interactionEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.setUrlPatterns(List.of("/api/interactions"));
        registration.setName("interactionEtagFilter");
        registration.setOrder(20);
        return registration;
    }
}
