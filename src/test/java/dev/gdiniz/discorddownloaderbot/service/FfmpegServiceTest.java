package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.testsupport.FakeTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void canFitInSize_subSecondClipDoesNotDivideByZero() {
        assertThat(service.canFitInSize(0.4, 26_214_400L)).isTrue();
    }

    @Test
    void canFitInSize_veryLongVideoIsRejected() {
        assertThat(service.canFitInSize(3 * 60 * 60, 10_485_760L)).isFalse();
    }

    @Test
    void imagesAreNeverReencoded() {
        assertThat(service.needsEncode(Path.of("photo.jpg"), "720p")).isFalse();
        assertThat(service.needsEncode(Path.of("photo.WEBP"), "original")).isFalse();
        assertThat(service.isImage(Path.of("photo.png"))).isTrue();
        assertThat(service.isImage(Path.of("clip.mp4"))).isFalse();
    }

    @Test
    void h264Mp4IsSentAsIsEvenWhenAQualityWasRequested(@TempDir Path tmp) {
        var fake = fakeService(tmp);
        assertThat(fake.needsEncode(Path.of("clip_h264.mp4"), "720p")).isFalse();
        assertThat(fake.needsEncode(Path.of("clip_audioonly.mp4"), "original")).isFalse();
    }

    @Test
    void mp4WithCodecsDiscordCannotPlayIsReencoded(@TempDir Path tmp) {
        var fake = fakeService(tmp);
        assertThat(fake.needsEncode(Path.of("clip_vp9.mp4"), "original")).isTrue();
        assertThat(fake.needsEncode(Path.of("clip_av1.mp4"), "original")).isTrue();
    }

    @Test
    void codecProbeReadsVideoAndAudioStreams(@TempDir Path tmp) {
        var info = fakeService(tmp).probeCodecs(Path.of("clip_vp9.mp4"));
        assertThat(info.videoCodec()).isEqualTo("vp9");
        assertThat(info.audioCodec()).isEqualTo("opus");
    }

    private static FfmpegService fakeService(Path tmp) {
        var tools = FakeTools.install(tmp.resolve("bin"));
        return new FfmpegService(FakeTools.properties(tools, tmp.resolve("work"), 10));
    }
}
