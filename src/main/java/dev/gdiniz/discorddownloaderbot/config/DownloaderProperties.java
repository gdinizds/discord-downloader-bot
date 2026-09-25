package dev.gdiniz.discorddownloaderbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("downloader")
public record DownloaderProperties(
        S3Properties s3,
        String ytdlpPath,
        String ffmpegPath,
        int ffmpegThreads,
        String tmpDir,
        long discordMaxFileBytes,
        int processingTimeoutSeconds
) {
    @ConstructorBinding
    public DownloaderProperties(
            S3Properties s3,
            String ytdlpPath,
            String ffmpegPath,
            @DefaultValue("2") int ffmpegThreads,
            String tmpDir,
            long discordMaxFileBytes,
            int processingTimeoutSeconds
    ) {
        this.s3 = s3;
        this.ytdlpPath = ytdlpPath;
        this.ffmpegPath = ffmpegPath;
        this.ffmpegThreads = ffmpegThreads <= 0 ? 2 : ffmpegThreads;
        this.tmpDir = tmpDir;
        this.discordMaxFileBytes = discordMaxFileBytes;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    public DownloaderProperties(
            S3Properties s3,
            String ytdlpPath,
            String ffmpegPath,
            String tmpDir,
            long discordMaxFileBytes,
            int processingTimeoutSeconds
    ) {
        this(s3, ytdlpPath, ffmpegPath, 2, tmpDir, discordMaxFileBytes, processingTimeoutSeconds);
    }

    public record S3Properties(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            String region
    ) {
        public S3Properties {
            if (region == null || region.isBlank()) {
                region = "garage";
            }
        }
    }
}
