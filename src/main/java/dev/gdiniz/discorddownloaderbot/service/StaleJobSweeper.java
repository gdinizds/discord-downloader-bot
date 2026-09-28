package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJobRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;

@Component
public class StaleJobSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleJobSweeper.class);
    static final String REASON = "Interrupted: worker stopped before the job finished";
    private static final EnumSet<DownloadStatus> ACTIVE = EnumSet.of(
            DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.ENCODING, DownloadStatus.UPLOADING);

    private final DownloadJobRepository jobRepository;
    private final DownloaderProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public StaleJobSweeper(DownloadJobRepository jobRepository, DownloaderProperties properties,
                           MeterRegistry meterRegistry) {
        this(jobRepository, properties, meterRegistry, Clock.systemUTC());
    }

    StaleJobSweeper(DownloadJobRepository jobRepository, DownloaderProperties properties,
                    MeterRegistry meterRegistry, Clock clock) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void sweep() {
        try {
            var now = OffsetDateTime.now(clock);
            var cutoff = now.minus(staleAfter());
            int updated = jobRepository.failStaleJobs(ACTIVE, cutoff, now, REASON);
            if (updated > 0) {
                log.warn("Marked {} stale download job(s) as FAILED (older than {})", updated, cutoff);
                meterRegistry.counter("downloader.job.stale").increment(updated);
            }
        } catch (Exception e) {
            log.warn("Stale job sweep failed: {}", e.getMessage());
        }
    }

    Duration staleAfter() {
        return Duration.ofSeconds(Math.max(300, properties.processingTimeoutSeconds() * 3L));
    }
}
