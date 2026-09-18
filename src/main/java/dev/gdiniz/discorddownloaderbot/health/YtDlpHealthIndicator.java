package dev.gdiniz.discorddownloaderbot.health;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ytDlp")
public class YtDlpHealthIndicator implements HealthIndicator {

    private final DownloaderProperties properties;

    public YtDlpHealthIndicator(DownloaderProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            var process = new ProcessBuilder(properties.ytdlpPath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            var version = new String(process.getInputStream().readAllBytes()).trim();
            int exit = process.waitFor();
            if (exit == 0 && !version.isBlank()) {
                return Health.up().withDetail("version", version).build();
            }
            return Health.down().withDetail("exitCode", exit).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
