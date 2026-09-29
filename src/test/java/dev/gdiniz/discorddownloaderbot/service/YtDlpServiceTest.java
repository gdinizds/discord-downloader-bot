package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.SourceBlockedException;
import dev.gdiniz.discorddownloaderbot.dto.VideoProbe;
import dev.gdiniz.discorddownloaderbot.testsupport.FakeTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YtDlpServiceTest {

    @TempDir
    Path tmp;

    private Path tools;
    private YtDlpService service;
    private final DownloadSource source = DownloadSource.generic("youtube.com");

    @BeforeEach
    void setUp() {
        tools = FakeTools.install(tmp.resolve("bin"));
        service = new YtDlpService(FakeTools.properties(tools, tmp.resolve("work"), 10));
    }

    @Test
    void execute_withNonExistentBinary_throwsDownloadException() {
        var s3Props = new DownloaderProperties.S3Properties("http://localhost:3900", "bucket", "key", "secret", "garage");
        var props = new DownloaderProperties(s3Props, "/nonexistent/yt-dlp", "/usr/bin/ffmpeg",
                tmp.toString(), 26214400L, 50);
        var missing = new YtDlpService(props);

        assertThatThrownBy(() -> missing.execute(request("corr-1", "https://youtube.com/watch?v=test"), source, null))
                .isInstanceOf(DownloadException.class);
    }

    @Test
    void probeEstimatesSizeFromRequestedFormatsAndKeepsInfoJson() {
        var probe = service.probe(request("corr-probe", "https://youtube.com/watch?v=ok"), source);

        assertThat(probe.fileSizeBytes()).isEqualTo(1500);
        assertThat(probe.durationSeconds()).isEqualTo(12.5);
        assertThat(probe.hasInfoJson()).isTrue();
        assertThat(probe.infoJson()).exists();
    }

    @Test
    void executeReusesProbeInsteadOfExtractingTwice() {
        var request = request("corr-reuse", "https://youtube.com/watch?v=ok");
        var probe = service.probe(request, source);

        var result = service.execute(request, source, probe);

        assertThat(result.filePath().getFileName().toString()).isEqualTo("clip.mp4");
        assertThat(result.fileSizeBytes()).isEqualTo(2048);
        var calls = FakeTools.calls(tools);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).contains("--load-info-json").doesNotContain("https://youtube.com/watch?v=ok");
    }

    @Test
    void urlIsPassedAfterEndOfOptionsMarker() {
        service.execute(request("corr-plain", "https://youtube.com/watch?v=ok"), source, VideoProbe.unknown());

        var call = FakeTools.calls(tools).getLast();
        assertThat(call).endsWith("\"--\", \"https://youtube.com/watch?v=ok\"]");
    }

    @Test
    void privateContentIsReportedAsUnavailable() {
        assertThatThrownBy(() -> service.probe(request("corr-private", "https://youtube.com/watch?v=private"), source))
                .isInstanceOf(ContentUnavailableException.class);
    }

    @Test
    void failedProbeFallsBackToPlainDownload() {
        var request = request("corr-noprobe", "https://youtube.com/watch?v=noprobe");

        var probe = service.probe(request, source);
        var result = service.execute(request, source, probe);

        assertThat(probe.hasInfoJson()).isFalse();
        assertThat(result.filePath()).exists();
        assertThat(FakeTools.calls(tools).getLast()).contains("https://youtube.com/watch?v=noprobe");
    }

    @Test
    void infrastructureErrorsAreNotClassifiedAsUnavailable() {
        assertThatThrownBy(() -> service.execute(request("corr-broken", "https://youtube.com/watch?v=broken"), source, null))
                .isInstanceOf(DownloadException.class)
                .isNotInstanceOf(ContentUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void slowDownloadIsKilledAtTheTimeout() {
        var quick = new YtDlpService(FakeTools.properties(tools, tmp.resolve("work"), 1));

        assertThatThrownBy(() -> quick.execute(request("corr-slow", "https://youtube.com/watch?v=sleep"), source, null))
                .isInstanceOf(DownloadInterruptedException.class);
    }

    @Test
    void partialFilesAndProbeJsonAreNeverPickedAsTheResult() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("pick"));
        Files.write(dir.resolve("video.mp4"), new byte[100]);
        Files.write(dir.resolve("video.mp4.part"), new byte[5000]);
        Files.write(dir.resolve("probe.info.json"), new byte[9000]);
        Files.write(dir.resolve("video.f137.mp4.ytdl"), new byte[7000]);

        assertThat(YtDlpService.findDownloadedFile(dir).getFileName().toString()).isEqualTo("video.mp4");
    }

    @Test
    void sizeEstimatePrefersExactSizeThenApproxThenFormats() {
        var json = JsonMapper.builder().build();

        assertThat(YtDlpService.estimateSize(json.readTree("{\"filesize\": 10, \"filesize_approx\": 20}"))).isEqualTo(10);
        assertThat(YtDlpService.estimateSize(json.readTree("{\"filesize\": null, \"filesize_approx\": 20}"))).isEqualTo(20);
        assertThat(YtDlpService.estimateSize(json.readTree("{\"requested_formats\": [{\"filesize\": 3}, {\"filesize_approx\": 4}]}"))).isEqualTo(7);
        assertThat(YtDlpService.estimateSize(json.readTree("{\"requested_formats\": [{\"filesize\": 3}, {}]}"))).isZero();
        assertThat(YtDlpService.estimateSize(json.readTree("{}"))).isZero();
    }

    private static DownloadRequest request(String correlationId, String url) {
        return new DownloadRequest(correlationId, "guild-1", "channel-1", "user-1",
                "token-1", "msg-1", url, "original");
    }

    @Test
    void qualitySelectorsPreferH264BeforeFallingBack() {
        var selector = service.resolveFormatSelector("table", "720p");

        assertThat(selector).startsWith("bestvideo[vcodec^=avc1][height<=720]+bestaudio[ext=m4a]");
        assertThat(selector).endsWith("/best[height<=720]");
        assertThat(service.resolveFormatSelector("table", "original")).isEqualTo("table");
    }

    @Test
    void genericSourcePrefersH264() {
        assertThat(DownloadSource.generic("example.com").getFormatSelector()).startsWith("best[vcodec^=avc1]");
    }

    @Test
    void rateLimitIsReportedAsBlockedAndStopsAtTheProbe() {
        assertThatThrownBy(() -> service.probe(request("corr-rl", "https://instagram.com/reel/ratelimit"), source))
                .isInstanceOf(SourceBlockedException.class)
                .isNotInstanceOf(ContentUnavailableException.class);
        assertThat(FakeTools.calls(tools)).hasSize(1);
    }

    @Test
    void privateContentWinsOverBlockedMarkers() {
        var failure = YtDlpService.classify("x", "ERROR: Private video. Sign in to confirm you've been granted access");

        assertThat(failure).isInstanceOf(ContentUnavailableException.class);
    }

    @Test
    void eachJobGetsItsOwnCookieCopyAndTheSharedFileIsNeverWritten() throws Exception {
        var shared = Files.writeString(tmp.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n");
        var igSource = DownloadSource.generic("instagram.com");
        ReflectionTestUtils.setField(igSource, "extraArgs",
                new String[]{"--no-playlist", "--cookies", shared.toString()});
        var request = request("corr-cookies", "https://instagram.com/reel/ok");

        var probe = service.probe(request, igSource);
        var result = service.execute(request, igSource, probe);

        var jobCookies = tmp.resolve("work").resolve("corr-cookies").resolve(".auth").resolve("cookies.txt");
        assertThat(Files.readString(shared)).isEqualTo("# Netscape HTTP Cookie File\n");
        assertThat(jobCookies).exists();
        assertThat(FakeTools.calls(tools)).allSatisfy(call ->
                assertThat(call).contains(jobCookies.toString()).doesNotContain("\"" + shared + "\""));
        assertThat(result.filePath().getFileName().toString()).isEqualTo("clip.mp4");
    }

    @Test
    void missingCookieFileIsPassedThroughUnchanged() {
        var cmd = new ArrayList<String>();
        YtDlpService.addExtraArgs(cmd, new String[]{"--cookies", "/nope/cookies.txt", "--no-progress"}, tmp);

        assertThat(cmd).containsExactly("--cookies", "/nope/cookies.txt");
    }
}
