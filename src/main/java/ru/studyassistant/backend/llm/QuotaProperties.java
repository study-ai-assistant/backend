package ru.studyassistant.backend.llm;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.llm-quota")
public class QuotaProperties implements InitializingBean {
    private int tokensPerMinute = 8000;
    private int requestsPerMinute = 30;
    private int tokensPerDay = 200000;
    private int requestsPerDay = 1000;
    private Duration minuteWindow = Duration.ofSeconds(61);
    private Duration dayWindow = Duration.ofHours(24);
    public int getTokensPerMinute() { return tokensPerMinute; }
    public void setTokensPerMinute(int v) { tokensPerMinute = v; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int v) { requestsPerMinute = v; }
    public int getTokensPerDay() { return tokensPerDay; }
    public void setTokensPerDay(int v) { tokensPerDay = v; }
    public int getRequestsPerDay() { return requestsPerDay; }
    public void setRequestsPerDay(int v) { requestsPerDay = v; }
    public Duration getMinuteWindow() { return minuteWindow; }
    public void setMinuteWindow(Duration v) { minuteWindow = v; }
    public Duration getDayWindow() { return dayWindow; }
    public void setDayWindow(Duration v) { dayWindow = v; }
    @Override
    public void afterPropertiesSet() {
        if (tokensPerMinute < 1 || requestsPerMinute < 1 || tokensPerDay < 1 || requestsPerDay < 1
                || minuteWindow == null || minuteWindow.toMillis() < 1
                || dayWindow == null || dayWindow.toMillis() < 1) {
            throw new IllegalArgumentException("Некорректные настройки app.llm-quota");
        }
    }
}
