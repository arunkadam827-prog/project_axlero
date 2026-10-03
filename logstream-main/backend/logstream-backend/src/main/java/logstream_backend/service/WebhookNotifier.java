package logstream_backend.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Delivers alert notifications to an external HTTP endpoint (Slack, PagerDuty,
 * Opsgenie, or a custom receiver).
 *
 * <p>
 * Webhook delivery is fire-and-forget from the alert engine's point of view:
 * failures are logged and swallowed so a misbehaving receiver can never stall
 * or crash the {@code @Scheduled} evaluation loop. See
 * {@code docs/IMPLEMENTATION_PLAN.md} week 4.
 * </p>
 */
@Service
public class WebhookNotifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebhookNotifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * POSTs a JSON alert payload to {@code webhookUrl}.
     *
     * @return {@code true} when the receiver responded with a 2xx status
     */
    public boolean send(
            String webhookUrl,
            String tenant,
            String ruleName,
            String severity,
            String message,
            long observedCount) {

        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "source", "logstream",
                    "tenant", tenant,
                    "rule", ruleName,
                    "severity", severity == null ? "warning" : severity,
                    "message", message,
                    "observedCount", observedCount,
                    "timestamp", Instant.now().toString()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;

            if (!ok) {
                System.out.println(
                        "Webhook returned HTTP " + response.statusCode()
                                + " for rule '" + ruleName + "'");
            }
            return ok;

        } catch (Exception e) {
            System.out.println(
                    "Webhook delivery failed for rule '" + ruleName + "': " + e.getMessage());
            return false;
        }
    }
}
