package ca.uqam.patchpilot.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/**
 * Network diagnostics endpoint.
 *
 * VULNERABILITY 3 — OS Command Injection (SonarQube rule: java:S2076)
 *
 * The {@code host} parameter is passed directly to {@code Runtime.exec()} without
 * validation or escaping. An attacker can inject shell metacharacters to execute
 * arbitrary commands on the host operating system.
 *
 * Example exploit: GET /api/diagnostics/ping?host=8.8.8.8;cat+/etc/passwd
 *
 * Secure fix: validate the input against a strict allowlist (e.g., a hostname
 * regex), use {@code ProcessBuilder} with a fixed command array so no shell
 * interpretation occurs, and set a working directory:
 *   new ProcessBuilder("ping", "-c", "1", validatedHost)
 *       .redirectErrorStream(true)
 *       .start();
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    @GetMapping("/ping")
    public ResponseEntity<String> ping(@RequestParam String host) throws Exception {
        // VULNERABILITY: unsanitised user input passed to Runtime.exec()
        var process = Runtime.getRuntime().exec("ping -c 1 " + host);
        var output = new BufferedReader(new InputStreamReader(process.getInputStream()))
                .lines()
                .collect(Collectors.joining("\n"));
        return ResponseEntity.ok(output);
    }
}
