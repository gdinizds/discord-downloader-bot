package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;

@Service
public class S3UploadService {

    private static final Logger log = LoggerFactory.getLogger(S3UploadService.class);

    private final S3Client s3Client;
    private final DownloaderProperties properties;

    public S3UploadService(S3Client s3Client, DownloaderProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @CircuitBreaker(name = "s3Upload", fallbackMethod = "s3Fallback")
    @Retry(name = "s3Retry")
    public String upload(Path filePath, String guildId, String correlationId) throws DownloadException {
        var fileName = filePath.getFileName().toString();
        var ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        var s3Key = "%s/%s%s".formatted(guildId, correlationId, ext);

        log.info("Uploading to S3: correlationId={} key={}", correlationId, s3Key);
        Span.current().setAttribute("s3.key", s3Key);

        long start = System.currentTimeMillis();
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.s3().bucket())
                            .key(s3Key)
                            .build(),
                    filePath
            );

            log.info("S3 upload completed in {}ms: correlationId={}", System.currentTimeMillis() - start, correlationId);
            return s3Key;

        } catch (Exception e) {
            throw new DownloadException("S3 upload failed", e);
        }
    }

    private String s3Fallback(Path filePath, String guildId, String correlationId, Throwable t) {
        log.error("S3 circuit breaker open: correlationId={}", correlationId, t);
        throw new DownloadException("S3 service temporarily unavailable", t);
    }
}
