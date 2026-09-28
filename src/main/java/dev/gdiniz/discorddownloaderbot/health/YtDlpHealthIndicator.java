package dev.gdiniz.discorddownloaderbot.health;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.util.ProcessRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component("ytDlp")
public class YtDlpHealthIndicator implements HealthIndicator {

    private final DownloaderProperties properties;
    private final CachedHealth cache = new CachedHealth(Duration.ofMinutes(5));

    public YtDlpHealthIndicator(DownloaderProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        return cache.get(this::check);
    }

    private Health check() {
        try {
            var result = ProcessRunner.run(List.of(properties.ytdlpPath(), "--version"), Duration.ofSeconds(10), 5, true);
            var version = result.output().strip();
            if (result.succeeded() && !version.isBlank()) {
                return Health.up().withDetail("version", version).build();
            }
            return Health.down().withDetail("exitCode", result.exitCode()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
