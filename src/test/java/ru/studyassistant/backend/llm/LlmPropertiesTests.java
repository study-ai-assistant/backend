package ru.studyassistant.backend.llm;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void bindsProviderModelAndKeyWithoutExposingSecretInToString() {
        context.withPropertyValues("app.llm.provider=groq", "app.llm.model=test-model",
                "app.llm.api-key=test-only-not-a-real-key", "app.llm.base-url=https://example.com")
                .run(app -> {
                    var config = app.getBean(LlmProperties.class);
                    assertThat(config.getProvider()).isEqualTo("groq");
                    assertThat(config.getModel()).isEqualTo("test-model");
                    assertThat(config.getApiKey()).isEqualTo("test-only-not-a-real-key");
                    assertThat(config.getBaseUrl()).isEqualTo("https://example.com");
                    assertThat(config.toString()).doesNotContain(config.getApiKey());
                });
    }

    @Test
    void startsWithoutLlmCredentials() {
        context.run(app -> assertThat(app.getBean(LlmProperties.class).getApiKey()).isEmpty());
    }

    @Test
    void readsApplicationYamlPlaceholdersFromEnvironment() {
        context.withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(app -> app.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                                "LLM_PROVIDER", "groq", "LLM_MODEL", "environment-model",
                                "LLM_API_KEY", "environment-test-secret",
                                "LLM_BASE_URL", "https://example.com/v1"))))
                .run(app -> {
                    var config = app.getBean(LlmProperties.class);
                    assertThat(config.getProvider()).isEqualTo("groq");
                    assertThat(config.getModel()).isEqualTo("environment-model");
                    assertThat(config.getApiKey()).isEqualTo("environment-test-secret");
                    assertThat(config.getBaseUrl()).isEqualTo("https://example.com/v1");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties.class)
    static class Config {}
}
