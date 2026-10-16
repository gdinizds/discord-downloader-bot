package dev.gdiniz.discorddownloaderbot.health;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.util.ProcessRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component("galleryDl")
public class GalleryDlHealthIndicator implements HealthIndicator {

    private final DownloaderProperties properties;
    private final CachedHealth cache = new CachedHealth(Duration.ofMinutes(5));

    public GalleryDlHealthIndicator(DownloaderProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        return cache.get(this::check);
    }

    private Health check() {
        try {
            var gdlResult = ProcessRunner.run(List.of(properties.galleryDlPath(), "--version"), Duration.ofSeconds(10), 5, true);
            var gdlVersion = gdlResult.output().strip();
            var ytResult = ProcessRunner.run(List.of(properties.ytdlpPath(), "--version"), Duration.ofSeconds(10), 5, true);
            var ytVersion = ytResult.output().strip();

            if (gdlResult.succeeded() && !gdlVersion.isBlank() && ytResult.succeeded() && !ytVersion.isBlank()) {
                return Health.up()
                        .withDetail("galleryDlVersion", gdlVersion)
                        .withDetail("ytDlpVersion", ytVersion)
                        .build();
            }
            return Health.down()
                    .withDetail("galleryDlExitCode", gdlResult.exitCode())
                    .withDetail("ytDlpExitCode", ytResult.exitCode())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
