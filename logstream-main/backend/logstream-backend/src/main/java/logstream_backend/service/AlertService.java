package logstream_backend.service;

import logstream_backend.entity.AlertRule;
import logstream_backend.entity.LogEntity;
import logstream_backend.repository.AlertRuleRepository;
import logstream_backend.repository.LogRepository;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {

    private final AlertRuleRepository alertRuleRepository;
    private final LogRepository logRepository;
    private final EmailService emailService;

    /*
     * Stores only the CURRENT alert state in memory.
     *
     * Nothing is saved in PostgreSQL.
     *
     * key   = rule id
     * value = true if alert is currently active
     */
    private final Map<Long, Boolean> activeAlerts =
            new HashMap<>();

    /*
     * Stores the current alert message for dashboard.
     *
     * Also memory only.
     */
    private final Map<Long, String> alertMessages =
            new HashMap<>();

    public AlertService(
            AlertRuleRepository alertRuleRepository,
            LogRepository logRepository,
            EmailService emailService) {

        this.alertRuleRepository = alertRuleRepository;
        this.logRepository = logRepository;
        this.emailService = emailService;
    }

    public AlertRule createRule(AlertRule rule) {

        rule.setEnabled(true);

        return alertRuleRepository.save(rule);
    }

    public List<AlertRule> getRules() {

        return alertRuleRepository.findAll();
    }

    public AlertRule updateRule(
            Long id,
            AlertRule updatedRule) {

        AlertRule rule =
                alertRuleRepository.findById(id)
                        .orElseThrow(
                                () -> new RuntimeException(
                                        "Alert rule not found"
                                )
                        );

        rule.setName(updatedRule.getName());
        rule.setService(updatedRule.getService());
        rule.setLevel(updatedRule.getLevel());
        rule.setThreshold(updatedRule.getThreshold());
        rule.setTimeWindowMinutes(
                updatedRule.getTimeWindowMinutes()
        );
        rule.setEnabled(updatedRule.isEnabled());

        return alertRuleRepository.save(rule);
    }

    public void deleteRule(Long id) {

        alertRuleRepository.deleteById(id);

        /*
         * Remove only in-memory state.
         */
        activeAlerts.remove(id);
        alertMessages.remove(id);
    }

    public Map<String, Object> getCurrentAlerts() {

        Map<String, Object> response =
                new HashMap<>();

        response.put(
                "hasAlert",
                !alertMessages.isEmpty()
        );

        response.put(
                "alerts",
                alertMessages
        );

        return response;
    }

    public void checkAlerts() {

        List<AlertRule> rules =
                alertRuleRepository
                        .findByEnabledTrue();

        for (AlertRule rule : rules) {

            checkSingleRule(rule);
        }
    }

    private void checkSingleRule(
            AlertRule rule) {

        LocalDateTime startTime =
                LocalDateTime.now()
                        .minusMinutes(
                                rule.getTimeWindowMinutes()
                        );

        List<LogEntity> logs;

        /*
         * If service is provided,
         * check service + level.
         */
        if (rule.getService() != null
                && !rule.getService().isBlank()) {

            logs =
                    logRepository
                            .findByCreatedAtAfterAndServiceAndLevel(
                                    startTime,
                                    rule.getService(),
                                    rule.getLevel()
                            );

        } else {

            /*
             * Otherwise check all services.
             */
            logs =
                    logRepository
                            .findByCreatedAtAfterAndLevel(
                                    startTime,
                                    rule.getLevel()
                            );
        }

        int count = logs.size();

        boolean conditionReached =
                count >= rule.getThreshold();

        boolean alreadyActive =
                activeAlerts.getOrDefault(
                        rule.getId(),
                        false
                );

        /*
         * ALERT CONDITION REACHED
         */
        if (conditionReached) {

            String message =
                    rule.getName()
                    + ": "
                    + count
                    + " "
                    + rule.getLevel()
                    + " logs detected in the last "
                    + rule.getTimeWindowMinutes()
                    + " minute(s).";

            /*
             * First time condition is reached.
             */
            if (!alreadyActive) {

                activeAlerts.put(
                        rule.getId(),
                        true
                );

                alertMessages.put(
                        rule.getId(),
                        message
                );

                /*
                 * Send EMAIL only once.
                 */
                try {

                    emailService.sendAlertEmail(
                            rule.getName(),
                            message
                    );

                    System.out.println(
                            "Alert email sent for: "
                            + rule.getName()
                    );

                } catch (Exception e) {

                    System.out.println(
                            "Failed to send alert email: "
                            + e.getMessage()
                    );
                }

                System.out.println(
                        "ALERT TRIGGERED: "
                        + message
                );
            }
        }

        /*
         * CONDITION IS CLEARED
         */
        else {

            if (alreadyActive) {

                /*
                 * Allow a future incident
                 * to generate another email.
                 */
                activeAlerts.remove(
                        rule.getId()
                );

                alertMessages.remove(
                        rule.getId()
                );

                System.out.println(
                        "Alert cleared: "
                        + rule.getName()
                );
            }
        }
    }
}