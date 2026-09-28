package dev.gdiniz.discorddownloaderbot.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;

public interface DownloadJobRepository extends JpaRepository<DownloadJob, Long> {

    Optional<DownloadJob> findByCorrelationId(String correlationId);

    boolean existsByCorrelationId(String correlationId);

    @Modifying
    @Transactional
    @Query("""
            update DownloadJob j
               set j.status = dev.gdiniz.discorddownloaderbot.domain.DownloadStatus.FAILED,
                   j.errorMessage = :reason,
                   j.completedAt = :now
             where j.status in :statuses
               and j.createdAt < :cutoff
            """)
    int failStaleJobs(@Param("statuses") Collection<DownloadStatus> statuses,
                      @Param("cutoff") OffsetDateTime cutoff,
                      @Param("now") OffsetDateTime now,
                      @Param("reason") String reason);
}
