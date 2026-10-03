package logstream_backend.repository;

import logstream_backend.entity.LogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Relational access to ingested logs.
 *
 * <p>
 * Every method here is tenant-scoped. There is deliberately no
 * {@code countAll()} style accessor — a query without a tenant filter would
 * violate the isolation contract (see {@code docs/MULTI_TENANCY.md} §4).
 * </p>
 */
public interface LogRepository extends JpaRepository<LogEntity, Long> {

        long countByTenantId(String tenantId);

        long countByTenantIdAndLevel(String tenantId, String level);

        List<LogEntity> findAllByTenantId(String tenantId);

        /**
         * Most recently created audit row. Used by the development seeder to
         * decide whether previously seeded sample data has aged out of the
         * dashboard's rolling time window and must be refreshed.
         */
        Optional<LogEntity> findTopByOrderByCreatedAtDesc();

        List<LogEntity> findByTenantIdAndCreatedAtAfterAndLevel(
                        String tenantId,
                        LocalDateTime time,
                        String level);

        List<LogEntity> findByTenantIdAndCreatedAtAfterAndServiceAndLevel(
                        String tenantId,
                        LocalDateTime time,
                        String service,
                        String level);
}
