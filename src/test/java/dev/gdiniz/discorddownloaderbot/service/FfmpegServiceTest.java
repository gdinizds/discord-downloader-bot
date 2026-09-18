package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegServiceTest {

    private FfmpegService service;

    @BeforeEach
    void setUp() {
        var s3Props = new DownloaderProperties.S3Properties("http://localhost:3900", "bucket", "key", "secret", "garage");
        var props = new DownloaderProperties(s3Props, "/usr/local/bin/yt-dlp", "/usr/bin/ffmpeg",
                "/tmp/discord-downloads", 26214400L, 50);
        service = new FfmpegService(props);
    }

    @Test
    void needsEncode_mp4WithOriginalQuality_returnsFalse() {
        assertThat(service.needsEncode(Path.of("video.mp4"), "original")).isFalse();
    }

    @Test
    void needsEncode_mp4With1080pQuality_returnsTrue() {
        assertThat(service.needsEncode(Path.of("video.mp4"), "1080p")).isTrue();
    }

    @Test
    void needsEncode_webmWithOriginalQuality_returnsTrue() {
        assertThat(service.needsEncode(Path.of("video.webm"), "original")).isTrue();
    }
}
