package ru.studyassistant.backend.material;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService service;

    public MaterialController(MaterialService service) {
        this.service = service;
    }

    @GetMapping("/modes")
    public ProcessingMode[] modes() {
        return ProcessingMode.values();
    }

    @PostMapping(value = "/materials/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProcessingResult process(@RequestParam("files") List<MultipartFile> files,
                                    @RequestParam("mode") ProcessingMode mode) {
        return service.process(files, mode);
    }
}
