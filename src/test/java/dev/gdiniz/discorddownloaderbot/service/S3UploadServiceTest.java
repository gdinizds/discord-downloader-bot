package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3UploadServiceTest {

    @Mock
    private S3Client s3Client;

    private S3UploadService service;

    @BeforeEach
    void setUp() {
        var s3Props = new DownloaderProperties.S3Properties("http://localhost:3900", "discord-attachments", "key", "secret", "garage");
        var props = new DownloaderProperties(s3Props, "/usr/local/bin/yt-dlp", "/usr/bin/ffmpeg",
                "/tmp/discord-downloads", 26214400L, 50);
        service = new S3UploadService(s3Client, props);
    }

    @Test
    void upload_success_returnsS3Key() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(Path.class))).thenReturn(null);

        var result = service.upload(Path.of("video.mp4"), "guild-1", "corr-1");

        assertThat(result).isEqualTo("guild-1/corr-1/video.mp4");
    }

    @Test
    void upload_s3Error_throwsDownloadException() {
        doThrow(S3Exception.builder().message("Connection refused").build())
                .when(s3Client).putObject(any(PutObjectRequest.class), any(Path.class));

        assertThatThrownBy(() -> service.upload(Path.of("video.mp4"), "guild-1", "corr-1"))
                .isInstanceOf(DownloadException.class)
                .hasMessageContaining("S3 upload failed");
    }
}
