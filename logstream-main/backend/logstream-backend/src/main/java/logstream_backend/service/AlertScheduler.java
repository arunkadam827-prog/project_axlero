package logstream_backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the alerting engine on a fixed cadence.
 *
 * <p>
 * Runs outside any request context, so it evaluates <em>all</em> tenants'
 * rules via {@link AlertService#checkAllTenants()} rather than relying on a
 * bound {@code TenantContext}. The 10s cadence matches the target documented in
 * {@code README.md} (<60s detection latency).
 * </p>
 */
@Component
public class AlertScheduler {

    private final AlertService alertService;

    public AlertScheduler(AlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedRate = 10000)
    public void checkAlerts() {

        try {
            alertService.checkAllTenants();
        } catch (Exception e) {
            System.out.println("Alert check failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
