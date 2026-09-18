package dev.gdiniz.discorddownloaderbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class YtDlpUpdater {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUpdater.class);

    @Scheduled(cron = "${downloader.ytdlp-update-cron:0 0 4 * * *}")
    public void update() {
        log.info("Checking for yt-dlp updates...");
        try {
            var process = new ProcessBuilder(
                    "pip3", "install", "--no-cache-dir", "--break-system-packages", "--quiet",
                    "-U", "yt-dlp[default]")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("yt-dlp update check completed successfully");
            } else {
                log.warn("yt-dlp update failed (exit {}): {}", exitCode, output);
            }
        } catch (IOException e) {
            log.warn("yt-dlp update failed to start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("yt-dlp update interrupted", e);
        }
    }
}
