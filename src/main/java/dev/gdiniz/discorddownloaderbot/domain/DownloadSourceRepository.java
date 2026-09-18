package dev.gdiniz.discorddownloaderbot.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DownloadSourceRepository extends JpaRepository<DownloadSource, Long> {

    Optional<DownloadSource> findByHostAndEnabledTrue(String host);
}
