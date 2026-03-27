package ca.uqam.patchpilot.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;

/**
 * File download endpoint for exported reports.
 *
 * VULNERABILITY 4 — Path Traversal (SonarQube rule: java:S2083)
 *
 * The {@code filename} parameter is appended to a base directory path without
 * canonicalisation or containment checks. An attacker can supply a value such as
 * {@code ../../etc/passwd} to read arbitrary files outside the intended directory.
 *
 * Secure fix: resolve the path, canonicalise it, and verify it still starts
 * with the expected base directory before opening the file:
 *   File resolved = new File(BASE_DIR, filename).getCanonicalFile();
 *   if (!resolved.toPath().startsWith(new File(BASE_DIR).getCanonicalPath())) {
 *       throw new SecurityException("Path traversal attempt detected");
 *   }
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String BASE_DIR = "/var/app/exports/";

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String filename) throws Exception {
        // VULNERABILITY: user-controlled input used to construct a file path
        var file = new File(BASE_DIR + filename);
        return ResponseEntity.ok(Files.readAllBytes(file.toPath()));
    }
}
