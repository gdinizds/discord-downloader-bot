package dev.gdiniz.discorddownloaderbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("downloader")
public record DownloaderProperties(
        S3Properties s3,
        String ytdlpPath,
        String ffmpegPath,
        String tmpDir,
        long discordMaxFileBytes,
        int processingTimeoutSeconds
) {
    public record S3Properties(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String region
    ) {}
}
