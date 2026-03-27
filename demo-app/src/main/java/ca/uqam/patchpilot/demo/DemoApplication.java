package ca.uqam.patchpilot.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the PatchPilot demo application.
 *
 * This application is an intentionally vulnerable Spring Boot REST API used
 * to demonstrate automated security remediation via the PatchPilot pipeline.
 * Each controller and service in this codebase contains a documented security
 * vulnerability that SonarQube is expected to detect and flag.
 *
 * Do not use this code as a template for production applications.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
