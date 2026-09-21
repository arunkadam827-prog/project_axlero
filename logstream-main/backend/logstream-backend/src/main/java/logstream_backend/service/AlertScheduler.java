package logstream_backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertScheduler {

    private final AlertService alertService;

    public AlertScheduler(AlertService alertService) {
        this.alertService = alertService;
    }

    /*
     * Check all enabled alert rules every 10 seconds.
     */
    @Scheduled(fixedRate = 10000)
    public void checkAlerts() {

        System.out.println("Checking alert rules...");

        try {
            alertService.checkAlerts();
        } catch (Exception e) {
            System.out.println(
                    "Alert check failed: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }
}