package logstream_backend.controller;

import logstream_backend.entity.AlertRule;
import logstream_backend.service.AlertService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin(origins = "http://localhost:5173")
public class AlertController {

    private final AlertService alertService;

    public AlertController(
            AlertService alertService) {

        this.alertService = alertService;
    }

    @PostMapping("/rules")
    public AlertRule createRule(
            @RequestBody AlertRule rule) {

        return alertService.createRule(rule);
    }

    @GetMapping("/rules")
    public List<AlertRule> getRules() {

        return alertService.getRules();
    }

    @PutMapping("/rules/{id}")
    public AlertRule updateRule(
            @PathVariable Long id,
            @RequestBody AlertRule rule) {

        return alertService.updateRule(
                id,
                rule
        );
    }

    @DeleteMapping("/rules/{id}")
    public void deleteRule(
            @PathVariable Long id) {

        alertService.deleteRule(id);
    }

    /*
     * Returns only CURRENT active alerts.
     *
     * No database history.
     */
    @GetMapping("/current")
    public Map<String, Object> getCurrentAlerts() {

        return alertService.getCurrentAlerts();
    }
}