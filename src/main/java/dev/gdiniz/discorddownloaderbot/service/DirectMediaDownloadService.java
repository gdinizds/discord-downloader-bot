package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.SourceBlockedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DirectMediaDownloadService {

    private static final Logger log = LoggerFactory.getLogger(DirectMediaDownloadService.class);

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "bmp", "tiff", "svg",
            "mp4", "webm", "mkv", "mov", "avi", "flv", "wmv", "m4v",
            "mp3", "m4a", "ogg", "opus", "wav", "flac", "aac"
    );

    private final DownloaderProperties properties;
    private final HttpClient httpClient;

    public DirectMediaDownloadService(DownloaderProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isDirectMediaUrl(String url) {
        try {
            var uri = URI.create(url);
            var path = uri.getPath();
            if (path == null) return false;
            int dot = path.lastIndexOf('.');
            if (dot >= 0) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                return MEDIA_EXTENSIONS.contains(ext);
            }
        } catch (Exception ignored) {}
        return false;
    }

    public DownloadResult download(DownloadRequest request, long maxBytes) throws DownloadException {
        Path outputDir = Path.of(properties.tmpDir(), request.correlationId());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new DownloadException("Failed to create download directory", e);
        }

        log.info("Attempting direct HTTP download: correlationId={} url={}", request.correlationId(), request.url());

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(Duration.ofSeconds(Math.max(1, properties.processingTimeoutSeconds())))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("Direct download interrupted", e);
        } catch (IOException e) {
            throw new DownloadException("Direct download network error: " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status == 404 || status == 410) {
            throw new ContentUnavailableException("Direct download returned HTTP " + status);
        }
        if (status == 401 || status == 403 || status == 429) {
            throw new SourceBlockedException("Direct download returned HTTP " + status);
        }
        if (status >= 400) {
            throw new DownloadException("Direct download failed with HTTP status " + status);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        String filename = deriveFilename(request.url(), response);
        Path targetFile = outputDir.resolve(filename);

        long written = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes * 2) { // cap at 2x server limit to avoid unlimited streaming
                    throw new DownloadException("Direct download stream exceeded maximum limit");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new DownloadException("Failed writing downloaded stream to disk", e);
        }

        String ext = getExtension(filename);
        String title = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;

        log.info("Direct HTTP download completed: correlationId={} file={} size={}",
                request.correlationId(), filename, written);

        return new DownloadResult(targetFile, title, written, ext, List.of(targetFile));
    }

    private String deriveFilename(String url, HttpResponse<?> response) {
        var disposition = response.headers().firstValue("Content-Disposition").orElse("");
        if (disposition.contains("filename=")) {
            int start = disposition.indexOf("filename=") + 9;
            String part = disposition.substring(start).strip();
            if (part.startsWith("\"") && part.length() > 1) {
                int end = part.indexOf('"', 1);
                if (end > 1) return sanitizeFilename(part.substring(1, end));
            } else {
                int semi = part.indexOf(';');
                return sanitizeFilename(semi > 0 ? part.substring(0, semi) : part);
            }
        }

        try {
            var uri = URI.create(url);
            var path = uri.getPath();
            if (path != null && path.contains("/")) {
                var candidate = path.substring(path.lastIndexOf('/') + 1);
                if (!candidate.isBlank() && candidate.contains(".")) {
                    return sanitizeFilename(candidate);
                }
            }
        } catch (Exception ignored) {}

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String ext = extensionFromMime(contentType);
        return "download." + ext;
    }

    private static String sanitizeFilename(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    private static String extensionFromMime(String mime) {
        if (mime == null) return "bin";
        String lower = mime.toLowerCase(Locale.ROOT);
        if (lower.contains("image/jpeg")) return "jpg";
        if (lower.contains("image/png")) return "png";
        if (lower.contains("image/webp")) return "webp";
        if (lower.contains("image/gif")) return "gif";
        if (lower.contains("video/mp4")) return "mp4";
        if (lower.contains("video/webm")) return "webm";
        if (lower.contains("audio/mpeg") || lower.contains("audio/mp3")) return "mp3";
        if (lower.contains("audio/ogg")) return "ogg";
        if (lower.contains("audio/aac") || lower.contains("audio/mp4")) return "m4a";
        return "bin";
    }
}
