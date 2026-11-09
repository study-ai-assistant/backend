package ru.studyassistant.backend.material;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService service;

    public MaterialController(MaterialService service) {
        this.service = service;
    }

    @PostMapping(value = "/materials/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProcessingResult process(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam("mode") ProcessingMode mode) {

        if (files.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Передайте ровно один файл (TXT или PDF)."); // ИЗМЕНИЛИ
        }
        MultipartFile file = files.getFirst();
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст или не был передан");
        }

        return service.process(file, mode);
    }
}