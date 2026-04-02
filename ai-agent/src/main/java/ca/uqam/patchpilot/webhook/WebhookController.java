package ca.uqam.patchpilot.webhook;

import ca.uqam.patchpilot.fix.FixPipelineService;
import ca.uqam.patchpilot.persistence.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Receives SonarQube webhook POST requests at /api/webhooks/sonarqube.
 *
 * This controller does exactly four things and nothing else:
 *   1. Validate the HMAC-SHA256 signature
 *   2. Parse just enough JSON to read taskId and status
 *   3. Insert idempotently (ON CONFLICT DO NOTHING)
 *   4. Return 200 immediately and dispatch async processing
 *
 * No fix logic runs here. Returning 200 quickly prevents SonarQube from
 * timing out and retrying the delivery.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    // SonarQube sends the HMAC in this header (lowercase hex-encoded SHA-256)
    private static final String HMAC_HEADER = "X-Sonar-Webhook-HMAC-SHA256";

    private final WebhookEventRepository webhookEventRepository;
    private final FixPipelineService fixPipelineService;
    private final ObjectMapper objectMapper;

    @Value("${sonarqube.webhook-secret}")
    private String webhookSecret;

    public WebhookController(WebhookEventRepository webhookEventRepository,
                             FixPipelineService fixPipelineService,
                             ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.fixPipelineService = fixPipelineService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/sonarqube")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = HMAC_HEADER, required = false) String receivedHmac,
            @RequestBody String rawBody) {

        // ── 1. Validate HMAC ─────────────────────────────────────────────────
        // SonarQube computes HMAC-SHA256(secret, rawBody) and sends the hex result
        // in the header. We recompute and compare.
        //
        // We return 200 even on rejection: returning 4xx would trigger SonarQube's
        // retry logic, flooding us with rejected requests on every bad delivery.
        if (receivedHmac == null || !isValidSignature(rawBody, receivedHmac)) {
            log.warn("Webhook rejected — invalid or missing HMAC signature");
            return ResponseEntity.ok().build();
        }

        // ── 2. Parse JSON ────────────────────────────────────────────────────
        SonarWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, SonarWebhookPayload.class);
        } catch (Exception e) {
            log.error("Webhook rejected — failed to parse JSON body", e);
            return ResponseEntity.ok().build();
        }

        log.info("Webhook received — taskId={} status={} project={}",
                payload.taskId(), payload.status(), payload.projectKey());

        // ── 3. Skip failed analyses ──────────────────────────────────────────
        // A FAILED SonarQube analysis produces no reliable findings to fix.
        if (!payload.isSuccess()) {
            log.info("Webhook ignored — SonarQube task status is {}, expected SUCCESS",
                    payload.status());
            return ResponseEntity.ok().build();
        }

        // ── 4. Idempotent insert ─────────────────────────────────────────────
        // Returns 1 if inserted, 0 if sonar_task_id already exists.
        // ON CONFLICT DO NOTHING handles SonarQube's at-least-once guarantee.
        int inserted = webhookEventRepository.insertIfAbsent(payload.taskId(), rawBody);
        if (inserted == 0) {
            log.info("Duplicate webhook delivery for taskId={} — ignored", payload.taskId());
            return ResponseEntity.ok().build();
        }

        // ── 5. Dispatch async processing and return immediately ──────────────
        // @Async in FixPipelineService puts this on a background thread.
        // SonarQube gets its 200 before any fix work starts.
        fixPipelineService.processWebhookEventAsync(payload.taskId());

        log.info("Webhook accepted — taskId={} dispatched to fix pipeline", payload.taskId());
        return ResponseEntity.ok().build();
    }

    /**
     * Recomputes HMAC-SHA256 of the raw body using the shared secret and
     * compares with the value SonarQube sent.
     *
     * MessageDigest.isEqual() is constant-time — prevents timing side-channel
     * attacks where an attacker could deduce the secret by measuring how long
     * a comparison takes.
     */
    private boolean isValidSignature(String body, String receivedHmac) {
        try {
            var keySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] computedBytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computedHmac = HexFormat.of().formatHex(computedBytes);

            return MessageDigest.isEqual(
                    computedHmac.getBytes(StandardCharsets.UTF_8),
                    receivedHmac.getBytes(StandardCharsets.UTF_8));

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC validation error — check sonarqube.webhook-secret config", e);
            return false;
        }
    }
}
