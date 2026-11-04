package ru.studyassistant.backend.material;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Публичный список режимов для мобильного клиента, доступен без ключа LLM. */
@RestController
public class ModesController {
    @GetMapping("/api/v1/modes")
    public ProcessingMode[] modes() {
        return ProcessingMode.values();
    }
}
