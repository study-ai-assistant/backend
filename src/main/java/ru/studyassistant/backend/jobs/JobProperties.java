package ru.studyassistant.backend.jobs;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.jobs")
public class JobProperties implements InitializingBean {
    private Duration retention = Duration.ofHours(1);
    private int capacity = 100;
    public Duration getRetention() { return retention; }
    public void setRetention(Duration value) { retention = value; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int value) { capacity = value; }
    @Override
    public void afterPropertiesSet() {
        if (retention == null || retention.toMillis() < 1 || capacity < 1) {
            throw new IllegalArgumentException("Некорректные настройки app.jobs");
        }
    }
}
