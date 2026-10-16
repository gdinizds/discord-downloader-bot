package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.SourceBlockedException;
import dev.gdiniz.discorddownloaderbot.testsupport.FakeTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GalleryDlServiceTest {

    @TempDir
    Path tmp;

    private Path tools;
    private GalleryDlService service;
    private final DownloadSource source = DownloadSource.generic("twitter.com");

    @BeforeEach
    void setUp() {
        tools = FakeTools.install(tmp.resolve("bin"));
        service = new GalleryDlService(FakeTools.properties(tools, tmp.resolve("work"), 10));
    }

    @Test
    void executeWithNonExistentBinaryThrowsDownloadException() {
        var s3Props = new DownloaderProperties.S3Properties("http://localhost:3900", "bucket", "key", "secret", "garage");
        var props = new DownloaderProperties(s3Props, "/usr/local/bin/yt-dlp", "/nonexistent/gallery-dl",
                "/usr/bin/ffmpeg", 2, tmp.toString(), 26214400L, 50);
        var missing = new GalleryDlService(props);

        assertThatThrownBy(() -> missing.execute(request("corr-1", "https://twitter.com/user/status/1"), source))
                .isInstanceOf(DownloadException.class);
    }

    @Test
    void executePassesUnifiedOptionsAndRange() {
        var req = request("corr-unified", "https://twitter.com/user/status/1");
        var result = service.execute(req, source);

        assertThat(result.filePath().getFileName().toString()).isEqualTo("gallery_photo.jpg");
        assertThat(result.fileSizeBytes()).isEqualTo(1024);

        var calls = FakeTools.calls(tools);
        assertThat(calls).isNotEmpty();
        var lastCall = calls.getLast();
        assertThat(lastCall).contains("extractor.ytdl.enabled=true");
        assertThat(lastCall).contains("extractor.ytdl.module=yt_dlp");
        assertThat(lastCall).contains("downloader.ytdl.forward-cookies=true");
        assertThat(lastCall).contains("--range");
        assertThat(lastCall).contains("1-10");
        assertThat(lastCall).contains("extractor.ytdl.raw-options");
    }

    @Test
    void youtubeUrlsArePrefixedWithYtdl() {
        var ytSource = DownloadSource.generic("youtube.com");
        var req = request("corr-yt", "https://youtube.com/watch?v=123");
        service.execute(req, ytSource);

        var lastCall = FakeTools.calls(tools).getLast();
        assertThat(lastCall).contains("\"ytdl:https://youtube.com/watch?v=123\"");
    }

    @Test
    void twitterUrlsAreNotPrefixedWithYtdl() {
        var req = request("corr-tw", "https://twitter.com/user/status/123");
        service.execute(req, source);

        var lastCall = FakeTools.calls(tools).getLast();
        assertThat(lastCall).contains("\"https://twitter.com/user/status/123\"");
        assertThat(lastCall).doesNotContain("\"ytdl:https://twitter.com/user/status/123\"");
    }

    @Test
    void qualitySelectorsPreferH264BeforeFallingBack() {
        var selector = GalleryDlService.resolveFormatSelector("table", "720p");

        assertThat(selector).startsWith("bestvideo[vcodec^=avc1][height<=720]+bestaudio[ext=m4a]");
        assertThat(selector).endsWith("/best");
        assertThat(GalleryDlService.resolveFormatSelector("table", "original")).isEqualTo("table");
    }

    @Test
    void eachJobGetsItsOwnCookieCopyAndTheSharedFileIsNeverWritten() throws Exception {
        var shared = Files.writeString(tmp.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n");
        var igSource = DownloadSource.generic("instagram.com");
        ReflectionTestUtils.setField(igSource, "extraArgs",
                new String[]{"--no-playlist", "--cookies", shared.toString()});
        var request = request("corr-cookies", "https://instagram.com/reel/ok");

        var result = service.execute(request, igSource);

        var jobCookies = tmp.resolve("work").resolve("corr-cookies").resolve(".auth").resolve("cookies.txt");
        assertThat(Files.readString(shared)).isEqualTo("# Netscape HTTP Cookie File\n");
        assertThat(jobCookies).exists();
        assertThat(FakeTools.calls(tools)).allSatisfy(call ->
                assertThat(call).contains(jobCookies.toString()).doesNotContain("\"" + shared + "\""));
        assertThat(result.filePath().getFileName().toString()).isEqualTo("gallery_photo.jpg");
    }

    @Test
    void privateContentIsReportedAsUnavailable() {
        assertThatThrownBy(() -> service.execute(request("corr-private", "https://twitter.com/private/status/1"), source))
                .isInstanceOf(ContentUnavailableException.class);
    }

    @Test
    void rateLimitIsReportedAsBlocked() {
        assertThatThrownBy(() -> service.execute(request("corr-rl", "https://instagram.com/reel/ratelimit"), source))
                .isInstanceOf(SourceBlockedException.class)
                .isNotInstanceOf(ContentUnavailableException.class);
    }

    @Test
    void infrastructureErrorsAreNotClassifiedAsUnavailable() {
        assertThatThrownBy(() -> service.execute(request("corr-broken", "https://twitter.com/broken/status/1"), source))
                .isInstanceOf(DownloadException.class)
                .isNotInstanceOf(ContentUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void slowDownloadIsKilledAtTheTimeout() {
        var quick = new GalleryDlService(FakeTools.properties(tools, tmp.resolve("work"), 1));

        assertThatThrownBy(() -> quick.execute(request("corr-slow", "https://twitter.com/sleep/1"), source))
                .isInstanceOf(DownloadInterruptedException.class);
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

    @Test
    void extractTitleFromTwitterMetadataExtractsContent() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("twitter-meta"));
        var image = dir.resolve("2109219814325600384_1.jpg");
        Files.write(image, new byte[100]);
        var meta = dir.resolve("2109219814325600384_1.jpg.json");
        Files.writeString(meta, "{\"tweet_id\": 2109219814325600384, \"content\": \"🚨URGENTE - Rui Costa Pimenta declara apoio a Flávio Bolsonaro\"}");

        String title = GalleryDlService.extractTitleFromMetadata(dir, image);

        assertThat(title).isEqualTo("🚨URGENTE - Rui Costa Pimenta declara apoio a Flávio Bolsonaro");
    }

    @Test
    void extractTitleFromInstagramMetadataExtractsDescription() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("insta-meta"));
        var image = dir.resolve("photo.jpg");
        Files.write(image, new byte[100]);
        var info = dir.resolve("info.json");
        Files.writeString(info, "{\"id\": 123, \"description\": \"Beautiful sunset &amp; clouds\"}");

        String title = GalleryDlService.extractTitleFromMetadata(dir, image);

        assertThat(title).isEqualTo("Beautiful sunset & clouds");
    }

    @Test
    void extractTitleReturnsNullWhenNoJsonPresent() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("no-meta"));
        var image = dir.resolve("2109219814325600384_1.jpg");
        Files.write(image, new byte[100]);

        String title = GalleryDlService.extractTitleFromMetadata(dir, image);

        assertThat(title).isNull();
    }

    @Test
    void executePreservesUtf8SpecialCharactersAndEmojisInTitle() {
        var req = request("corr-utf8", "https://instagram.com/reel/utf8");
        var result = service.execute(req, source);

        assertThat(result.title()).isEqualTo("Virou programador, deve tá rico agora 🤡💻 #viral");
    }

    private static DownloadRequest request(String correlationId, String url) {
        return new DownloadRequest(correlationId, "guild-1", "channel-1", "user-1",
                "token-1", "msg-1", url, "original");
    }
}
