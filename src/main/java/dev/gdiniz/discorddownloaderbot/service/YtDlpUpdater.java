package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class YtDlpUpdater {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUpdater.class);
    private static final Duration UPDATE_TIMEOUT = Duration.ofSeconds(120);

    @Scheduled(cron = "${downloader.ytdlp-update-cron:0 0 4 * * *}")
    public void update() {
        log.info("Checking for yt-dlp and gallery-dl updates...");
        try {
            var result = ProcessRunner.run(List.of(
                    "pip3", "install", "--user", "--no-cache-dir", "--break-system-packages", "--quiet",
                    "--only-binary", ":all:", "-U", "yt-dlp[default,curl-cffi]", "gallery-dl"),
                    UPDATE_TIMEOUT, 50, true);

            if (result.succeeded()) {
                log.info("yt-dlp and gallery-dl update check completed successfully");
            } else {
                log.warn("Tools update failed (exit {}): {}", result.exitCode(), result.output());
            }
        } catch (TimeoutException e) {
            log.warn("Tools update timed out after {}s", UPDATE_TIMEOUT.toSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tools update interrupted", e);
        } catch (Exception e) {
            log.warn("Tools update failed to execute", e);
        }
    }
}
