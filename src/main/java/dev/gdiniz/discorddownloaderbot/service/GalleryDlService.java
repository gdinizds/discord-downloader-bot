package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.SourceBlockedException;
import dev.gdiniz.discorddownloaderbot.util.MetadataUtils;
import dev.gdiniz.discorddownloaderbot.util.ProcessRunner;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Service
public class GalleryDlService {

    private static final Logger log = LoggerFactory.getLogger(GalleryDlService.class);
    private static final int MAX_LOG_LINES = 50;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> COOKIE_FLAGS = Set.of("--cookies", "--cookies-from-browser");

    private static final List<String> UNAVAILABLE_MARKERS = List.of(
            "http error 404",
            "http error 401",
            "not found",
            "does not exist",
            "private account",
            "post deleted",
            "content deleted",
            "user has deleted this post"
    );

    private static final List<String> BLOCKED_MARKERS = List.of(
            "http error 429",
            "rate-limit",
            "rate limit",
            "login required",
            "checkpoint required",
            "challenge required"
    );

    private final DownloaderProperties properties;

    public GalleryDlService(DownloaderProperties properties) {
        this.properties = properties;
    }

    @CircuitBreaker(name = "gallerydl", fallbackMethod = "galleryDlFallback")
    @Retry(name = "gallerydlRetry")
    @Bulkhead(name = "gallerydlBulkhead")
    public DownloadResult execute(DownloadRequest request, DownloadSource source) throws DownloadException {
        Path outputDir = Path.of(properties.tmpDir(), request.correlationId());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new DownloadException("Failed to create download directory", e);
        }

        var cmd = new ArrayList<String>();
        cmd.add(properties.galleryDlPath());
        cmd.add("-d");
        cmd.add(outputDir.toString());
        cmd.add("-D");
        cmd.add("");
        cmd.add("--write-metadata");
        cmd.add("--write-info-json");

        addCookiesIfPresent(cmd, source.getExtraArgs(), outputDir);

        cmd.add("--");
        cmd.add(request.url());

        log.info("Starting gallery-dl download: correlationId={} url={}", request.correlationId(), request.url());
        Span.current().setAttribute("gallerydl.url", request.url());

        long start = System.currentTimeMillis();
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(cmd, timeout(), MAX_LOG_LINES, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("gallery-dl execution interrupted", e);
        } catch (TimeoutException e) {
            throw new DownloadInterruptedException("gallery-dl timed out", e);
        } catch (IOException e) {
            throw new DownloadException("gallery-dl process execution failed", e);
        }

        try {
            var files = findDownloadedFiles(outputDir);
            if (!files.isEmpty()) {
                var primaryFile = files.stream()
                        .max(Comparator.comparingLong(GalleryDlService::sizeOrZero))
                        .orElse(files.getFirst());
                long totalSize = files.stream().mapToLong(GalleryDlService::sizeOrZero).sum();
                String fileName = primaryFile.getFileName().toString();
                int dotIdx = fileName.lastIndexOf('.');
                String ext = dotIdx >= 0 ? fileName.substring(dotIdx + 1) : "jpg";
                String fallbackName = dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;

                String title = extractTitleFromMetadata(outputDir, primaryFile);
                if (title == null || title.isBlank()) {
                    title = fallbackName;
                }

                log.info("gallery-dl completed in {}ms: correlationId={} files={} totalSize={} title={}",
                        System.currentTimeMillis() - start, request.correlationId(), files.size(), totalSize, title);
                return new DownloadResult(primaryFile, title, totalSize, ext, files);
            }
        } catch (IOException e) {
            log.warn("Failed scanning for downloaded files: correlationId={}", request.correlationId(), e);
        }

        if (!result.succeeded()) {
            log.error("gallery-dl failed (exit {}): correlationId={}\n{}",
                    result.exitCode(), request.correlationId(), result.output());
            throw classify("gallery-dl exited with code " + result.exitCode(), result.output());
        }

        throw new DownloadException("No files downloaded by gallery-dl");
    }

    static List<Path> findDownloadedFiles(Path outputDir) throws IOException {
        try (var stream = Files.list(outputDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> isMediaCandidate(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        }
    }

    static String extractTitleFromMetadata(Path outputDir, Path primaryFile) {
        return MetadataUtils.extractTitleFromMetadata(outputDir, primaryFile, null);
    }

    static String readTitleFromJson(Path jsonPath) {
        return MetadataUtils.readTitleFromJson(jsonPath);
    }

    private static String unescapeHtml(String text) {
        return MetadataUtils.unescapeHtml(text);
    }

    private static boolean isMediaCandidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.endsWith(".part")
                && !lower.endsWith(".tmp")
                && !lower.endsWith(".temp")
                && !lower.endsWith(".json")
                && !lower.endsWith(".txt")
                && !lower.endsWith(".aria2");
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private void addCookiesIfPresent(List<String> cmd, String[] extraArgs, Path workDir) {
        Path existingCopy = workDir.resolve(".auth").resolve("cookies.txt");
        if (Files.exists(existingCopy)) {
            cmd.add("--cookies");
            cmd.add(existingCopy.toString());
            return;
        }

        if (extraArgs == null) return;
        for (int i = 0; i < extraArgs.length; i++) {
            String arg = extraArgs[i];
            if (COOKIE_FLAGS.contains(arg) && i + 1 < extraArgs.length) {
                Path original = Path.of(extraArgs[++i]);
                if (Files.exists(original)) {
                    try {
                        Path authDir = workDir.resolve(".auth");
                        Files.createDirectories(authDir);
                        Path copy = authDir.resolve("cookies.txt");
                        Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
                        cmd.add("--cookies");
                        cmd.add(copy.toString());
                        return;
                    } catch (IOException e) {
                        log.warn("Could not copy cookies for gallery-dl: {}", e.getMessage());
                        cmd.add("--cookies");
                        cmd.add(original.toString());
                        return;
                    }
                }
            }
        }
    }

    private static DownloadException classify(String message, String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        for (String marker : BLOCKED_MARKERS) {
            if (lower.contains(marker)) {
                return new SourceBlockedException(message + ": " + output);
            }
        }
        for (String marker : UNAVAILABLE_MARKERS) {
            if (lower.contains(marker)) {
                return new ContentUnavailableException(message + ": " + output);
            }
        }
        return new DownloadException(message + ": " + output);
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, properties.processingTimeoutSeconds()));
    }

    private DownloadResult galleryDlFallback(DownloadRequest request, DownloadSource source, Throwable t) {
        if (t instanceof DownloadException downloadException) throw downloadException;
        log.error("gallery-dl circuit breaker open or bulkhead full: correlationId={}", request.correlationId(), t);
        throw new DownloadException("gallery-dl service temporarily unavailable", t);
    }
}
