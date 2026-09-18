package dev.gdiniz.discorddownloaderbot.health;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ffmpeg")
public class FfmpegHealthIndicator implements HealthIndicator {

    private final DownloaderProperties properties;

    public FfmpegHealthIndicator(DownloaderProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            var process = new ProcessBuilder(properties.ffmpegPath(), "-version")
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit == 0) {
                var firstLine = output.lines().findFirst().orElse("unknown");
                return Health.up().withDetail("version", firstLine).build();
            }
            return Health.down().withDetail("exitCode", exit).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
