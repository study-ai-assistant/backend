package ru.studyassistant.backend.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PdfPropertiesTests {
    @Test
    void bindsLimitsFromConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("app.pdf.max-pages=12", "app.pdf.timeout=3s", "app.pdf.max-concurrent=1",
                        "app.pdf.worker-heap-mb=64")
                .run(context -> {
                    var properties = context.getBean(PdfProperties.class);
                    assertThat(properties.getMaxPages()).isEqualTo(12);
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getMaxConcurrent()).isEqualTo(1);
                    assertThat(properties.getWorkerHeapMb()).isEqualTo(64);
                });
    }

    @Test
    void rejectsInvalidLimits() {
        var properties = new PdfProperties();
        properties.setMaxPages(0);
        assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
        properties.setMaxPages(50);
        properties.setTimeout(Duration.ZERO);
        assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PdfProperties.class)
    static class Config { }
}
