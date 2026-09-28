package dev.gdiniz.discorddownloaderbot.health;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.util.ProcessRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component("ffmpeg")
public class FfmpegHealthIndicator implements HealthIndicator {

    private final DownloaderProperties properties;
    private final CachedHealth cache = new CachedHealth(Duration.ofMinutes(5));

    public FfmpegHealthIndicator(DownloaderProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        return cache.get(this::check);
    }

    private Health check() {
        try {
            var result = ProcessRunner.run(List.of(properties.ffmpegPath(), "-version"), Duration.ofSeconds(10), 50, true);
            if (result.succeeded()) {
                var firstLine = result.output().lines().findFirst().orElse("unknown");
                return Health.up().withDetail("version", firstLine).build();
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
