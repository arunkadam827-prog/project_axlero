package logstream_backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "logs")
public class LogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String timestamp;

    private String level;

    private String service;

    @Column(columnDefinition = "TEXT")
    private String message;

    private String host;

    private LocalDateTime createdAt;

    public LogEntity() {
    }

    public LogEntity(
            String timestamp,
            String level,
            String service,
            String message,
            String host) {

        this.timestamp = timestamp;
        this.level = level;
        this.service = service;
        this.message = message;
        this.host = host;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setId(Long id) {
        this.id = id;
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

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}