package ru.studyassistant.backend.material;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Хранит лимиты материала и отдельных частей для LLM. */
@Component
@ConfigurationProperties(prefix = "app.text-splitting")
public class TextSplittingProperties implements InitializingBean {

    private int maxCharacters = 24_000;
    private int maxEstimatedTokens = 10_000;
    private int singleRequestTextTokens = 4_800;
    private int partTextTokens = 2_000;
    private int maxParts = 6;

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    public int getMaxEstimatedTokens() {
        return maxEstimatedTokens;
    }

    public void setMaxEstimatedTokens(int maxEstimatedTokens) {
        this.maxEstimatedTokens = maxEstimatedTokens;
    }

    public int getSingleRequestTextTokens() {
        return singleRequestTextTokens;
    }

    public void setSingleRequestTextTokens(int singleRequestTextTokens) {
        this.singleRequestTextTokens = singleRequestTextTokens;
    }

    public int getPartTextTokens() {
        return partTextTokens;
    }

    public void setPartTextTokens(int partTextTokens) {
        this.partTextTokens = partTextTokens;
    }

    public int getMaxParts() {
        return maxParts;
    }

    public void setMaxParts(int maxParts) {
        this.maxParts = maxParts;
    }

    @Override
    public void afterPropertiesSet() {
        if (maxCharacters < 1 || maxEstimatedTokens < 1 || singleRequestTextTokens < 1
                || partTextTokens < 1 || maxParts < 1) {
            throw new IllegalArgumentException("Лимиты разбиения текста должны быть положительными.");
        }
    }
}
