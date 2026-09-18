package dev.gdiniz.discorddownloaderbot.health;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@Component("s3")
public class S3HealthIndicator implements HealthIndicator {

    private final S3Client s3Client;
    private final DownloaderProperties properties;

    public S3HealthIndicator(S3Client s3Client, DownloaderProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(properties.s3().bucket())
                    .build());
            return Health.up().withDetail("bucket", properties.s3().bucket()).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("bucket", properties.s3().bucket()).build();
        }
    }
}
