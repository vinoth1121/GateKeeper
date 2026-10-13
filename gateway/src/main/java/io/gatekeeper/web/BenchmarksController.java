package io.gatekeeper.web;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves BENCHMARKS.md from the classpath as text/plain. The benchmarks wave
 * drops the file into the jar root; until then this answers honestly with a
 * 404 and a plain-text "not generated yet".
 */
@RestController
public class BenchmarksController {

    @GetMapping(value = "/api/benchmarks", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> benchmarks() {
        ClassPathResource resource = new ClassPathResource("BENCHMARKS.md");
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("not generated yet");
        }
        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("not generated yet");
        }
    }
}
