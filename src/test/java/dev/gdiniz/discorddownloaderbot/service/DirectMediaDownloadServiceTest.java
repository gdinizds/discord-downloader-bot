package dev.gdiniz.discorddownloaderbot.service;

import com.sun.net.httpserver.HttpServer;
import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectMediaDownloadServiceTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private int port;
    private DirectMediaDownloadService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        port = server.getAddress().getPort();
        server.start();

        UrlPolicy testPolicy = new UrlPolicy() {
            @Override
            public Optional<String> rejectionReason(String url) {
                if (url != null && url.startsWith("http://127.0.0.1:" + port)) {
                    return Optional.empty();
                }
                return super.rejectionReason(url);
            }
        };

        var props = new DownloaderProperties(
                new DownloaderProperties.S3Properties("http://localhost:3900", "b", "k", "s", "garage"),
                "/usr/local/bin/yt-dlp", "/usr/local/bin/gallery-dl", "/usr/bin/ffmpeg", 2,
                tmp.toString(), 26214400L, 5);

        service = new DirectMediaDownloadService(props, testPolicy);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

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

    @Test
    void followsValidRedirectsSuccessfully() {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/media.jpg");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        server.createContext("/media.jpg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            byte[] body = "dummy image data".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });

        var req = new DownloadRequest("corr-redir", "g1", "c1", "u1", "t1", null,
                "http://127.0.0.1:" + port + "/redirect", "original");

        var result = service.download(req, 10000);

        assertThat(result.filePath()).exists();
        assertThat(result.filePath().getFileName().toString()).isEqualTo("media.jpg");
        assertThat(result.extension()).isEqualTo("jpg");
    }

    @Test
    void redirectBlockedWhenTargetResolvesToInternalAddress() {
        server.createContext("/redirect-internal", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://10.0.0.1/admin/metadata");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        var req = new DownloadRequest("corr-ssrf-redir", "g1", "c1", "u1", "t1", null,
                "http://127.0.0.1:" + port + "/redirect-internal", "original");

        assertThatThrownBy(() -> service.download(req, 10000))
                .isInstanceOf(DownloadException.class)
                .hasMessageContaining("Direct download redirect blocked");
    }

    @Test
    void redirectLoopExceedingMaxHopsFails() {
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        var req = new DownloadRequest("corr-hops", "g1", "c1", "u1", "t1", null,
                "http://127.0.0.1:" + port + "/loop", "original");

        assertThatThrownBy(() -> service.download(req, 10000))
                .isInstanceOf(DownloadException.class)
                .hasMessageContaining("maximum redirect hops");
    }

    @Test
    void contentLengthHeaderExceedingLimitFailsFast() {
        server.createContext("/large.mp4", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.sendResponseHeaders(200, 100000000L);
                OutputStream os = exchange.getResponseBody();
                os.write(new byte[1024]);
                os.flush();
            } catch (Exception ignored) {}
        });

        var req = new DownloadRequest("corr-toolarge", "g1", "c1", "u1", "t1", null,
                "http://127.0.0.1:" + port + "/large.mp4", "original");

        assertThatThrownBy(() -> service.download(req, 10000))
                .isInstanceOf(DownloadException.class)
                .hasMessageContaining("Content-Length (100000000) exceeds maximum limit");
    }
}
