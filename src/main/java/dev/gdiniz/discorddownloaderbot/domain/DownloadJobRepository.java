package dev.gdiniz.discorddownloaderbot.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DownloadJobRepository extends JpaRepository<DownloadJob, Long> {

    Optional<DownloadJob> findByCorrelationId(String correlationId);
}
