package logstream_backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Relational (PostgreSQL) projection of an ingested log line.
 *
 * <p>
 * PostgreSQL is intentionally <em>not</em> the search store — Lucene is. This
 * table exists for audit, retention management and relational joins needed by
 * the alerting engine. It is therefore kept narrow and indexed on the columns
 * the alert engine filters by ({@code tenant_id}, {@code level},
 * {@code createdAt}). See {@code docs/INDEXING_STRATEGY.md} §2.
 * </p>
 */
@Entity
@Table(name = "logs", indexes = {
        @Index(name = "idx_logs_tenant_created", columnList = "tenantId,createdAt"),
        @Index(name = "idx_logs_tenant_level_created", columnList = "tenantId,level,createdAt")
})
public class LogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning tenant — every read path must filter on this.
     *
     * <p>
     * The {@code default 'default'} column definition lets Hibernate add this
     * {@code NOT NULL} column to a pre-existing {@code logs} table, backfilling
     * legacy rows instead of failing with
     * "column tenant_id contains null values".
     * </p>
     */
    @Column(nullable = false, columnDefinition = "varchar(255) default 'default'")
    private String tenantId;

    private String timestamp;

    private String level;

    private String service;

    @Column(columnDefinition = "TEXT")
    private String message;

    private String host;

    private String traceId;

    private String errorCode;

    /** Request/operation duration in milliseconds. */
    private Long responseTime;

    private LocalDateTime createdAt;

    public LogEntity() {
    }

    /**
     * Backwards-compatible constructor that assumes the default tenant and no
     * extended observability fields.
     */
    public LogEntity(
            String timestamp,
            String level,
            String service,
            String message,
            String host) {

        this("default",
                timestamp,
                level,
                service,
                message,
                host,
                null,
                null,
                0L);
    }

    public LogEntity(
            String tenantId,
            String timestamp,
            String level,
            String service,
            String message,
            String host,
            String traceId,
            String errorCode,
            long responseTime) {

        this.tenantId = tenantId;
        this.timestamp = timestamp;
        this.level = level;
        this.service = service;
        this.message = message;
        this.host = host;
        this.traceId = traceId;
        this.errorCode = errorCode;
        this.responseTime = responseTime;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getLevel() {
        return level;
    }

    public String getService() {
        return service;
    }

    public String getMessage() {
        return message;
    }

    public String getHost() {
        return host;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Long getResponseTime() {
        return responseTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public void setService(String service) {
        this.service = service;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public void setResponseTime(Long responseTime) {
        this.responseTime = responseTime;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
