package ca.uqam.patchpilot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test — verifies that the Spring context loads without errors.
 * This is the minimum test required for the Maven verify phase to pass.
 */
@SpringBootTest
class DemoApplicationTest {

    @Test
    void contextLoads() {
        // If the Spring context fails to start, this test fails.
        // No assertions needed — the test passes simply by reaching this line.
    }
}
