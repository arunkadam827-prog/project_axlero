package logstream_backend.repository;

import logstream_backend.entity.LogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LogRepository extends JpaRepository<LogEntity, Long> {

    long countByLevel(String level);

    List<LogEntity> findByCreatedAtAfterAndLevel(
            LocalDateTime time,
            String level
    );

    List<LogEntity> findByCreatedAtAfterAndServiceAndLevel(
            LocalDateTime time,
            String service,
            String level
    );
}