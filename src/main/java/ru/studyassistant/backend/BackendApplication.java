package ru.studyassistant.backend;

import java.io.IOException;
import java.util.Arrays;
import ru.studyassistant.backend.material.PdfWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "--internal-pdf-worker".equals(args[0])) {
            PdfWorker.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(BackendApplication.class, args);
    }
}
