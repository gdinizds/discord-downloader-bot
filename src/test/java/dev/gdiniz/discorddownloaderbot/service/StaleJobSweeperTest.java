package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJobRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaleJobSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private final DownloadJobRepository repository = mock(DownloadJobRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void failsOnlyActiveJobsOlderThanThreeTimesTheTimeout() {
        var sweeper = sweeper(200);
        when(repository.failStaleJobs(any(), any(), any(), anyString())).thenReturn(3);

        sweeper.sweep();

        var now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        verify(repository).failStaleJobs(
                argThat((Collection<DownloadStatus> s) -> s.containsAll(java.util.List.of(
                        DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.ENCODING, DownloadStatus.UPLOADING))
                        && !s.contains(DownloadStatus.DONE) && !s.contains(DownloadStatus.FAILED)),
                eq(now.minusSeconds(600)), eq(now), eq(StaleJobSweeper.REASON));
        assertThat(meters.counter("downloader.job.stale").count()).isEqualTo(3.0);
    }

    @Test
    void shortTimeoutsStillWaitAtLeastFiveMinutes() {
        assertThat(sweeper(50).staleAfter()).hasSeconds(300);
    }

    @Test
    void databaseErrorsDoNotPropagate() {
        when(repository.failStaleJobs(any(), any(), any(), anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> sweeper(50).sweep()).doesNotThrowAnyException();
    }

    private StaleJobSweeper sweeper(int timeoutSeconds) {
        var s3 = new DownloaderProperties.S3Properties("http://garage", "bucket", "k", "s", "garage");
        var props = new DownloaderProperties(s3, "yt-dlp", "ffmpeg", "/tmp", 1L, timeoutSeconds);
        return new StaleJobSweeper(repository, props, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
