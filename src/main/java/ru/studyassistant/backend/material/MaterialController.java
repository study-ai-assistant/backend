package ru.studyassistant.backend.material;

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
            @RequestParam("file") MultipartFile file,
            @RequestParam("mode") ProcessingMode mode) {
        
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пуст или не был передан");
        }
        
        return service.process(file, mode);
    }
}
