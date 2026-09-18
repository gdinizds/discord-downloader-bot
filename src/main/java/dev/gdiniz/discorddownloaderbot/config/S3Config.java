package dev.gdiniz.discorddownloaderbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(DownloaderProperties properties) {
        var s3Props = properties.s3();
        return S3Client.builder()
                .endpointOverride(URI.create(s3Props.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3Props.accessKey(), s3Props.secretKey())))
                .region(Region.of(s3Props.region() != null ? s3Props.region() : "garage"))
                .forcePathStyle(true)
                .build();
    }
}
