package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectMediaDownloadServiceTest {

    private final DownloaderProperties props = new DownloaderProperties(
            new DownloaderProperties.S3Properties("http://localhost:3900", "b", "k", "s", "garage"),
            "/usr/local/bin/yt-dlp", "/usr/local/bin/gallery-dl", "/usr/bin/ffmpeg", 2,
            "/tmp", 26214400L, 50);
    private final DirectMediaDownloadService service = new DirectMediaDownloadService(props);

    @Test
    void detectsDirectMediaUrlsCorrectly() {
        assertThat(service.isDirectMediaUrl("https://example.com/cat.jpg")).isTrue();
        assertThat(service.isDirectMediaUrl("https://example.com/photo.PNG")).isTrue();
        assertThat(service.isDirectMediaUrl("https://example.com/song.mp3")).isTrue();
        assertThat(service.isDirectMediaUrl("https://example.com/clip.mp4")).isTrue();
        assertThat(service.isDirectMediaUrl("https://example.com/audio.ogg")).isTrue();
        assertThat(service.isDirectMediaUrl("https://youtube.com/watch?v=123")).isFalse();
        assertThat(service.isDirectMediaUrl("https://twitter.com/user/status/123")).isFalse();
        assertThat(service.isDirectMediaUrl("not-a-url")).isFalse();
    }
}
