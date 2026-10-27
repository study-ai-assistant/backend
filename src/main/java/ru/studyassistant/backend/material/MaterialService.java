package ru.studyassistant.backend.material;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialService {
    public ProcessingResult process(List<MultipartFile> files, ProcessingMode mode) {
        if (files.isEmpty() || files.stream().anyMatch(MultipartFile::isEmpty)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Загрузите непустые файлы");
        }
        // TODO: проверить формат, извлечь текст через TextExtractor, ограничить длину,
        // затем передать текст и режим в LlmClient. Не сохранять документы и ключи в логах.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Обработка материалов ещё не реализована");
    }
}
