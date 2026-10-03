package logstream_backend.controller;

import logstream_backend.entity.AlertEvent;
import logstream_backend.entity.AlertRule;
import logstream_backend.service.AlertService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Alert rule management and alert state, all tenant-scoped via
 * {@code X-Tenant-Id}.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping("/rules")
    public AlertRule createRule(@RequestBody AlertRule rule) {
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

        return alertService.updateRule(id, rule);
    }

    @DeleteMapping("/rules/{id}")
    public void deleteRule(@PathVariable Long id) {
        alertService.deleteRule(id);
    }

    /** Currently firing alerts (live state). */
    @GetMapping("/current")
    public Map<String, Object> getCurrentAlerts() {
        return alertService.getCurrentAlerts();
    }

    /** Recent alert history (FIRING / CLEARED events). */
    @GetMapping("/history")
    public List<AlertEvent> getHistory() {
        return alertService.getHistory();
    }

    /** Forces an immediate evaluation of the caller's tenant rules. */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate() {
        alertService.checkAlerts();
        return Map.of("triggered", true);
    }
}
