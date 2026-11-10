package ru.studyassistant.backend.material;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Ограничения отдельного процесса разбора PDF. */
@Component
@ConfigurationProperties(prefix = "app.pdf")
public class PdfProperties implements InitializingBean {
    private int maxPages = 50;
    private Duration timeout = Duration.ofSeconds(10);
    private int maxConcurrent = 2;
    private int workerHeapMb = 128;
    private Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));

    @Override
    public void afterPropertiesSet() {
        if (maxPages < 1 || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.toMillis() < 1 || maxConcurrent < 1 || maxConcurrent > 8
                || workerHeapMb < 32 || workerHeapMb > 512 || tempDirectory == null
                || !Files.isDirectory(tempDirectory) || !Files.isWritable(tempDirectory)) {
            throw new IllegalArgumentException("Некорректные настройки app.pdf");
        }
    }

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { maxPages = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int value) { maxConcurrent = value; }
    public int getWorkerHeapMb() { return workerHeapMb; }
    public void setWorkerHeapMb(int value) { workerHeapMb = value; }
    public Path getTempDirectory() { return tempDirectory; }
    public void setTempDirectory(Path value) { tempDirectory = value; }
}
