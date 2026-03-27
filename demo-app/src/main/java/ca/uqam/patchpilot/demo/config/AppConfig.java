package ca.uqam.patchpilot.demo.config;

import org.springframework.context.annotation.Configuration;

/**
 * Application configuration.
 *
 * VULNERABILITY 2 — Hardcoded Password (SonarQube rule: java:S2068)
 *
 * Embedding credentials as string literals means they are stored in plain text
 * in the compiled bytecode and in version control history. Any developer or tool
 * with access to the repository or the JAR can recover these values trivially.
 *
 * Secure fix: read credentials from environment variables or a secrets manager:
 *   @Value("${app.integration.password}") private String integrationPassword;
 * and supply the value via an environment variable or a vault (e.g., Azure Key Vault,
 * HashiCorp Vault, AWS Secrets Manager).
 */
@Configuration
public class AppConfig {

    // VULNERABILITY: password hard-coded as a string literal
    private static final String INTEGRATION_PASSWORD = "sup3rS3cr3tP@ssw0rd"; // pragma: allowlist secret

    private static final String INTEGRATION_USER = "svc-integration";

    public String getIntegrationUser() {
        return INTEGRATION_USER;
    }

    public String getIntegrationPassword() {
        return INTEGRATION_PASSWORD;
    }
}
