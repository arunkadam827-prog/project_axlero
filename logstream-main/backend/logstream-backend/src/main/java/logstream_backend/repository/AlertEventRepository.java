package logstream_backend.repository;

import logstream_backend.entity.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertEventRepository
        extends JpaRepository<AlertEvent, Long> {

    List<AlertEvent> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    void deleteByRuleId(Long ruleId);
}
