package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class YtDlpServiceTest {

    private YtDlpService service;

    @BeforeEach
    void setUp() throws Exception {
        var s3Props = new DownloaderProperties.S3Properties("http://localhost:3900", "bucket", "key", "secret", "garage");
        var props = new DownloaderProperties(s3Props, "/nonexistent/yt-dlp", "/usr/bin/ffmpeg",
                "/tmp/discord-downloads", 26214400L, 50);
        service = new YtDlpService(props);
    }

    @Test
    void execute_withNonExistentBinary_throwsDownloadException() {
        var source = buildSource("best[ext=mp4]/best", new String[]{"--no-playlist"});
        var request = new DownloadRequest("corr-1", "guild-1", "channel-1", "user-1",
                "token-1", "msg-1", "https://youtube.com/watch?v=test", "original");

        assertThatThrownBy(() -> service.execute(request, source))
                .isInstanceOf(DownloadException.class);
    }

    private DownloadSource buildSource(String formatSelector, String[] extraArgs) {
        try {
            var source = new DownloadSource();
            setField(source, "host", "youtube.com");
            setField(source, "formatSelector", formatSelector);
            setField(source, "extraArgs", extraArgs);
            setField(source, "enabled", true);
            return source;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
