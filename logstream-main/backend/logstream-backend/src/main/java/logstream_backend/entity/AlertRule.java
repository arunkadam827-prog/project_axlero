package logstream_backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "alert_rules")
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String service;

    private String level;

    private Integer threshold;

    private Integer timeWindowMinutes;

    private boolean enabled;

    public AlertRule() {
    }

    public Long getId() {
        return id;
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

    public Integer getThreshold() {
        return threshold;
    }

    public Integer getTimeWindowMinutes() {
        return timeWindowMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setId(Long id) {
        this.id = id;
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

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public void setTimeWindowMinutes(Integer timeWindowMinutes) {
        this.timeWindowMinutes = timeWindowMinutes;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}