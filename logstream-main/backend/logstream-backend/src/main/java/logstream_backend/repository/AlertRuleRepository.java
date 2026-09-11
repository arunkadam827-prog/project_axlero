package logstream_backend.repository;

import logstream_backend.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository
        extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findByEnabledTrue();
}