package ru.studyassistant.backend.jobs;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.studyassistant.backend.material.MaterialService;
import ru.studyassistant.backend.material.ProcessingMode;

@RestController
public class JobController {
    private final JobService jobs;
    private final MaterialService materials;
    public JobController(JobService jobs, MaterialService materials) { this.jobs = jobs; this.materials = materials; }

    @PostMapping(value = "/api/v1/jobs/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobView> text(@RequestBody Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("text", "mode")) || !(request.get("text") instanceof String text)
                || !(request.get("mode") instanceof String mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Передайте только строки text и mode.");
        }
        ProcessingMode parsed;
        try { parsed = ProcessingMode.valueOf(mode); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неподдерживаемый режим обработки.");
        }
        return accepted(jobs.submit(text, parsed));
    }

    @PostMapping(value = "/api/v1/jobs/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobView> file(@RequestParam("file") List<MultipartFile> files,
                                       @RequestParam("mode") ProcessingMode mode) {
        if (files.size() != 1 || files.getFirst().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Передайте ровно один непустой TXT или PDF.");
        }
        return accepted(jobs.submit(materials.extract(files.getFirst(), 24000), mode));
    }

    @GetMapping("/api/v1/jobs/{id}")
    public ResponseEntity<JobView> status(@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Retry-After", "3").body(jobs.get(id));
    }

    private ResponseEntity<JobView> accepted(JobView view) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/" + view.id()))
                .header("Cache-Control", "no-store").header("Retry-After", "3").body(view);
    }
}
