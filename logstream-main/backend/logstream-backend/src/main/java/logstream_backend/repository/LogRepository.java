package logstream_backend.repository;

import logstream_backend.entity.LogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogRepository extends JpaRepository<LogEntity, Long> {

    long countByLevel(String level);
}