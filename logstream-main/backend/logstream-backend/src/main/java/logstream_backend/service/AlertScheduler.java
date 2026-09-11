package logstream_backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertScheduler {

    private final AlertService alertService;

    public AlertScheduler(
            AlertService alertService) {

        this.alertService = alertService;
    }

}