package logstream_backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Immutable audit record of an alert firing (or clearing).
 *
 * <p>
 * Persisted so the dashboard can show an alert history instead of only the
 * transient in-memory state the original implementation kept.
 * </p>
 */
@Entity
@Table(name = "alert_events", indexes = {
        @Index(name = "idx_events_tenant_created", columnList = "tenantId,createdAt"),
        @Index(name = "idx_events_rule", columnList = "ruleId,createdAt")
})
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "varchar(255) default 'default'")
    private String tenantId;

    private Long ruleId;

    private String ruleName;

    private String severity;

    /** FIRING or CLEARED. */
    private String state;

    @Column(columnDefinition = "TEXT")
    private String message;

    private Long observedCount;

    private Integer threshold;

    private LocalDateTime createdAt = LocalDateTime.now();

    public AlertEvent() {
    }

    public AlertEvent(
            String tenantId,
            Long ruleId,
            String ruleName,
            String severity,
            String state,
            String message,
            Long observedCount,
            Integer threshold) {

        this.tenantId = tenantId;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.state = state;
        this.message = message;
        this.observedCount = observedCount;
        this.threshold = threshold;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getSeverity() {
        return severity;
    }

    public String getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public Long getObservedCount() {
        return observedCount;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
