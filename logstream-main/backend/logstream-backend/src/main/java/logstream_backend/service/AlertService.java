package logstream_backend.service;

import logstream_backend.analytics.LogAnalyticsService;
import logstream_backend.entity.AlertEvent;
import logstream_backend.entity.AlertRule;
import logstream_backend.repository.AlertEventRepository;
import logstream_backend.repository.AlertRuleRepository;
import logstream_backend.tenant.TenantContext;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-driven alerting engine.
 *
 * <p>
 * Each enabled rule is evaluated against the <em>tenant's own</em> Lucene index
 * for the trailing {@code timeWindowMinutes}: the rule's query is parsed by
 * {@link LogAnalyticsService} and the hit count compared with the threshold.
 * Supporting facets and the query language means a rule can express
 * {@code level:ERROR AND response_time > 1000} rather than only a level filter.
 * </p>
 *
 * <p>
 * The engine follows an edge-triggered model: notifications fire on the
 * transition into the firing state and clear when the condition subsides, which
 * prevents alert storms. Every transition is persisted as an {@link AlertEvent}
 * for the dashboard history. See {@code docs/IMPLEMENTATION_PLAN.md} week 4.
 * </p>
 */
@Service
public class AlertService {

        private final AlertRuleRepository alertRuleRepository;
        private final AlertEventRepository alertEventRepository;
        private final LogAnalyticsService analyticsService;
        private final EmailService emailService;
        private final WebhookNotifier webhookNotifier;

        /** rule id -> true while the condition holds. */
        private final Map<Long, Boolean> activeAlerts = new java.util.concurrent.ConcurrentHashMap<>();

        /** rule id -> current message shown on the dashboard. */
        private final Map<Long, String> alertMessages = new java.util.concurrent.ConcurrentHashMap<>();

        public AlertService(
                        AlertRuleRepository alertRuleRepository,
                        AlertEventRepository alertEventRepository,
                        LogAnalyticsService analyticsService,
                        EmailService emailService,
                        WebhookNotifier webhookNotifier) {

                this.alertRuleRepository = alertRuleRepository;
                this.alertEventRepository = alertEventRepository;
                this.analyticsService = analyticsService;
                this.emailService = emailService;
                this.webhookNotifier = webhookNotifier;
        }

        // ------------------------------------------------------------------
        // CRUD (tenant scoped)
        // ------------------------------------------------------------------

        public AlertRule createRule(AlertRule rule) {

                if (rule.getTenantId() == null || rule.getTenantId().isBlank()) {
                        rule.setTenantId(TenantContext.get());
                }
                rule.setEnabled(true);

                if (rule.getThreshold() == null) {
                        rule.setThreshold(10);
                }
                if (rule.getTimeWindowMinutes() == null) {
                        rule.setTimeWindowMinutes(5);
                }
                if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
                        rule.setSeverity("warning");
                }
                if (rule.getChannel() == null || rule.getChannel().isBlank()) {
                        rule.setChannel("email");
                }

                return alertRuleRepository.save(rule);
        }

        public List<AlertRule> getRules() {
                return alertRuleRepository.findAllByTenantId(TenantContext.get());
        }

        public AlertRule updateRule(Long id, AlertRule updatedRule) {

                AlertRule rule = alertRuleRepository.findById(id)
                                .orElseThrow(() -> new RuntimeException("Alert rule not found"));

                requireSameTenant(rule.getTenantId());

                rule.setName(updatedRule.getName());
                rule.setService(updatedRule.getService());
                rule.setLevel(updatedRule.getLevel());
                rule.setQuery(updatedRule.getQuery());
                rule.setSeverity(updatedRule.getSeverity());
                rule.setChannel(updatedRule.getChannel());
                rule.setWebhookUrl(updatedRule.getWebhookUrl());
                rule.setThreshold(updatedRule.getThreshold());
                rule.setTimeWindowMinutes(updatedRule.getTimeWindowMinutes());
                rule.setEnabled(updatedRule.isEnabled());

                // Reset edge state so a changed rule can fire afresh.
                activeAlerts.remove(id);
                alertMessages.remove(id);

                return alertRuleRepository.save(rule);
        }

        public void deleteRule(Long id) {

                AlertRule rule = alertRuleRepository.findById(id)
                                .orElseThrow(() -> new RuntimeException("Alert rule not found"));

                requireSameTenant(rule.getTenantId());

                alertRuleRepository.deleteById(id);
                alertEventRepository.deleteByRuleId(id);
                activeAlerts.remove(id);
                alertMessages.remove(id);
        }

        /** Current (live) alerts for the caller's tenant. */
        public Map<String, Object> getCurrentAlerts() {

                List<AlertRule> rules = alertRuleRepository.findAllByTenantId(TenantContext.get());

                List<Map<String, Object>> alerts = new ArrayList<>();
                for (AlertRule rule : rules) {
                        if (alertMessages.containsKey(rule.getId())) {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("ruleId", rule.getId());
                                item.put("ruleName", rule.getName());
                                item.put("severity", rule.getSeverity());
                                item.put("message", alertMessages.get(rule.getId()));
                                item.put("firedAt", rule.getLastTriggeredAt());
                                alerts.add(item);
                        }
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("hasAlert", !alerts.isEmpty());
                response.put("alerts", alerts);
                return response;
        }

        /** Recent alert history for the caller's tenant. */
        public List<AlertEvent> getHistory() {
                return alertEventRepository
                                .findTop100ByTenantIdOrderByCreatedAtDesc(TenantContext.get());
        }

        // ------------------------------------------------------------------
        // Evaluation
        // ------------------------------------------------------------------

        /** Evaluates enabled rules for the caller's tenant (on-demand). */
        public void checkAlerts() {
                for (AlertRule rule : alertRuleRepository.findAllByTenantIdAndEnabledTrue(TenantContext.get())) {
                        evaluate(rule);
                }
        }

        /**
         * Evaluates every enabled rule across all tenants. Called by the scheduler,
         * which runs outside any request context and therefore cannot rely on
         * {@link TenantContext}.
         */
        public void checkAllTenants() {
                for (AlertRule rule : alertRuleRepository.findByEnabledTrue()) {
                        evaluate(rule);
                }
        }

        private void evaluate(AlertRule rule) {

                int windowMinutes = rule.getTimeWindowMinutes() == null
                                ? 5
                                : rule.getTimeWindowMinutes();
                int threshold = rule.getThreshold() == null ? 10 : rule.getThreshold();

                long to = System.currentTimeMillis();
                long from = to - (Math.max(windowMinutes, 1) * 60_000L);

                long count;
                try {
                        count = analyticsService.count(
                                        rule.getTenantId(), buildQuery(rule), from, to);
                } catch (Exception e) {
                        System.out.println(
                                        "Alert evaluation failed for rule '" + rule.getName()
                                                        + "': " + e.getMessage());
                        return;
                }

                boolean reached = count >= threshold;
                boolean alreadyActive = activeAlerts.getOrDefault(rule.getId(), false);

                if (reached && !alreadyActive) {
                        fire(rule, count, windowMinutes);
                } else if (!reached && alreadyActive) {
                        clear(rule);
                } else if (reached) {
                        // Refresh the live message with the latest count.
                        alertMessages.put(rule.getId(), buildMessage(rule, count, windowMinutes));
                }
        }

        private void fire(AlertRule rule, long count, int windowMinutes) {

                String message = buildMessage(rule, count, windowMinutes);

                activeAlerts.put(rule.getId(), true);
                alertMessages.put(rule.getId(), message);
                rule.setLastTriggeredAt(LocalDateTime.now());
                alertRuleRepository.save(rule);

                alertEventRepository.save(new AlertEvent(
                                rule.getTenantId(),
                                rule.getId(),
                                rule.getName(),
                                rule.getSeverity(),
                                "FIRING",
                                message,
                                count,
                                rule.getThreshold()));

                notify(rule, message, count);

                System.out.println("ALERT TRIGGERED [" + rule.getTenantId() + "]: " + message);
        }

        private void clear(AlertRule rule) {

                activeAlerts.remove(rule.getId());
                alertMessages.remove(rule.getId());

                alertEventRepository.save(new AlertEvent(
                                rule.getTenantId(),
                                rule.getId(),
                                rule.getName(),
                                rule.getSeverity(),
                                "CLEARED",
                                "Condition cleared for rule '" + rule.getName() + "'",
                                0L,
                                rule.getThreshold()));

                System.out.println(
                                "Alert cleared [" + rule.getTenantId() + "]: " + rule.getName());
        }

        private void notify(AlertRule rule, String message, long count) {

                String channel = rule.getChannel() == null ? "email" : rule.getChannel().toLowerCase();

                if ("email".equals(channel) || "both".equals(channel)) {
                        try {
                                emailService.sendAlertEmail(rule.getName(), message);
                        } catch (Exception e) {
                                System.out.println("Failed to send alert email: " + e.getMessage());
                        }
                }

                if ("webhook".equals(channel) || "both".equals(channel)) {
                        webhookNotifier.send(
                                        rule.getWebhookUrl(),
                                        rule.getTenantId(),
                                        rule.getName(),
                                        rule.getSeverity(),
                                        message,
                                        count);
                }
        }

        /** Composes the effective query from the rule's query / level / service. */
        private String buildQuery(AlertRule rule) {

                if (rule.getQuery() != null && !rule.getQuery().isBlank()) {
                        return rule.getQuery();
                }

                List<String> clauses = new ArrayList<>();

                if (rule.getLevel() != null && !rule.getLevel().isBlank()
                                && !"ALL".equalsIgnoreCase(rule.getLevel())) {
                        clauses.add("level:" + rule.getLevel());
                }
                if (rule.getService() != null && !rule.getService().isBlank()
                                && !"ALL".equalsIgnoreCase(rule.getService())) {
                        clauses.add("service:\"" + rule.getService() + "\"");
                }

                return String.join(" AND ", clauses);
        }

        private String buildMessage(AlertRule rule, long count, int windowMinutes) {
                return rule.getName()
                                + ": " + count + " matching logs in the last "
                                + windowMinutes + " minute(s) (threshold "
                                + rule.getThreshold() + ")";
        }

        private void requireSameTenant(String ownerTenant) {
                if (!TenantContext.get().equals(ownerTenant)) {
                        throw new SecurityException("Alert rule belongs to another tenant");
                }
        }
}
