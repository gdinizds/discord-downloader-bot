package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.SourceBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GalleryDlServiceTest {

    @TempDir
    Path tmp;

    private GalleryDlService service;

    @BeforeEach
    void setUp() {
        var s3Props = new DownloaderProperties.S3Properties("http://localhost:3900", "bucket", "key", "secret", "garage");
        var props = new DownloaderProperties(s3Props, "/usr/local/bin/yt-dlp", "/usr/local/bin/gallery-dl",
                "/usr/bin/ffmpeg", 2, tmp.toString(), 26214400L, 50);
        service = new GalleryDlService(props);
    }

    @Test
    void executeWithNonExistentBinaryThrowsDownloadException() {
        var req = new DownloadRequest("corr-1", "g1", "c1", "u1", "t1", null, "https://twitter.com/user/status/1", "original");
        var source = DownloadSource.generic("twitter.com");

        assertThatThrownBy(() -> service.execute(req, source))
                .isInstanceOf(DownloadException.class);
    }

    @Test
    void findDownloadedFilesFindsMediaCandidatesAndIgnoresTemp() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("scan"));
        Files.write(dir.resolve("image1.jpg"), new byte[100]);
        Files.write(dir.resolve("image2.png"), new byte[200]);
        Files.write(dir.resolve("meta.json"), new byte[50]);
        Files.write(dir.resolve("data.tmp"), new byte[50]);
        Files.write(dir.resolve("temp.part"), new byte[50]);

        var files = GalleryDlService.findDownloadedFiles(dir);

        assertThat(files).hasSize(2);
        assertThat(files.stream().map(p -> p.getFileName().toString())).containsExactly("image1.jpg", "image2.png");
    }
}
