package ru.studyassistant.backend.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Настройки будущего клиента. Секрет не включается в строковое представление. */
@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {
    private String provider = "";
    private String model = "";
    private String apiKey = "";
    private String baseUrl = "";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String toString() {
        return "LlmProperties[credentials=REDACTED]";
    }
}
