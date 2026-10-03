package logstream_backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A saved, tenant-scoped alerting rule.
 *
 * <p>
 * A rule is evaluated by querying the tenant's Lucene index for {@link #query}
 * over the trailing {@link #timeWindowMinutes} and comparing the hit count to
 * {@link #threshold}. This makes the rule expressive enough for
 * {@code level:ERROR AND response_time > 1000} instead of only a level filter.
 * </p>
 */
@Entity
@Table(name = "alert_rules", indexes = {
        @Index(name = "idx_rules_tenant_enabled", columnList = "tenantId,enabled")
})
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant. */
    @Column(nullable = false, columnDefinition = "varchar(255) default 'default'")
    private String tenantId = "default";

    private String name;

    /** Optional exact service filter (kept for simple rules / the UI). */
    private String service;

    /** Optional exact level filter (kept for simple rules / the UI). */
    private String level;

    /**
     * LogStream query language expression, e.g.
     * {@code level:ERROR AND service:auth}.
     */
    @Column(length = 1024)
    private String query;

    /** Severity label: info / warning / critical. */
    private String severity = "warning";

    /** Recipe channel: email / webhook / both. */
    private String channel = "email";

    /** Destination URL for webhook delivery. */
    private String webhookUrl;

    private Integer threshold;

    private Integer timeWindowMinutes;

    private boolean enabled;

    /** When the rule last transitioned into the firing state. */
    private LocalDateTime lastTriggeredAt;

    public AlertRule() {
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getService() {
        return service;
    }

    public String getLevel() {
        return level;
    }

    public String getQuery() {
        return query;
    }

    public String getSeverity() {
        return severity;
    }

    public String getChannel() {
        return channel;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public Integer getTimeWindowMinutes() {
        return timeWindowMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setService(String service) {
        this.service = service;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public void setTimeWindowMinutes(Integer timeWindowMinutes) {
        this.timeWindowMinutes = timeWindowMinutes;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setLastTriggeredAt(LocalDateTime lastTriggeredAt) {
        this.lastTriggeredAt = lastTriggeredAt;
    }
}
