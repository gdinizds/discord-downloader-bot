package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.VideoProbe;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Service
public class YtDlpService {

    private static final Logger log = LoggerFactory.getLogger(YtDlpService.class);
    private static final int MAX_LOG_LINES = 50;
    private static final String INFO_JSON = "probe.info.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> MANAGED_FLAGS = Set.of("--no-playlist", "--no-progress");
    private static final List<String> UNAVAILABLE_MARKERS = List.of(
            "unsupported url",
            "video unavailable",
            "private video",
            "this video is private",
            "has been removed",
            "no longer available",
            "http error 404",
            "no video formats found",
            "no video could be found",
            "there is no video in this post",
            "is not a valid url");

    private final DownloaderProperties properties;

    public YtDlpService(DownloaderProperties properties) {
        this.properties = properties;
    }

    @CircuitBreaker(name = "ytdlp", fallbackMethod = "ytdlpFallback")
    @Retry(name = "ytdlpRetry")
    @Bulkhead(name = "ytdlpBulkhead")
    public DownloadResult execute(DownloadRequest request, DownloadSource source, VideoProbe probe) throws DownloadException {
        if (Thread.currentThread().isInterrupted()) {
            throw new DownloadInterruptedException("yt-dlp skipped — processing timed out", null);
        }
        var outputDir = workDir(request);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new DownloadException("Failed to create temp directory", e);
        }

        var formatSelector = resolveFormatSelector(source.getFormatSelector(), request.qualidade());
        var outputTemplate = outputDir.resolve("%(title).150B.%(ext)s").toString();
        var command = new ArrayList<String>();
        command.add(properties.ytdlpPath());
        command.add("--no-playlist");
        command.add("--no-progress");
        command.add("-f");
        command.add(formatSelector);
        command.add("-o");
        command.add(outputTemplate);
        addExtraArgs(command, source.getExtraArgs());
        if (probe != null && probe.hasInfoJson() && Files.exists(probe.infoJson())) {
            command.add("--load-info-json");
            command.add(probe.infoJson().toString());
        } else {
            command.add("--");
            command.add(request.url());
        }

        log.info("Executing yt-dlp: correlationId={} url={} reusingProbe={}",
                request.correlationId(), request.url(), probe != null && probe.hasInfoJson());
        Span.current().setAttribute("download.url", request.url());

        long start = System.currentTimeMillis();
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(command, timeout(), MAX_LOG_LINES, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("yt-dlp interrupted", e);
        } catch (TimeoutException e) {
            throw new DownloadInterruptedException("yt-dlp timed out", e);
        } catch (IOException e) {
            throw new DownloadException("yt-dlp execution error", e);
        }

        if (!result.succeeded()) {
            log.error("yt-dlp failed (exit {}): correlationId={}\n{}", result.exitCode(), request.correlationId(), result.output());
            throw classify("yt-dlp exited with code " + result.exitCode(), result.output());
        }

        try {
            var downloadedFile = findDownloadedFile(outputDir);
            long size = Files.size(downloadedFile);
            String fileName = downloadedFile.getFileName().toString();
            int dotIdx = fileName.lastIndexOf('.');
            String ext = dotIdx >= 0 ? fileName.substring(dotIdx + 1) : "mp4";
            String name = dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;

            log.info("yt-dlp completed in {}ms: correlationId={} size={}",
                    System.currentTimeMillis() - start, request.correlationId(), size);
            return new DownloadResult(downloadedFile, name, size, ext);
        } catch (IOException e) {
            throw new DownloadException("Failed to read downloaded file", e);
        }
    }

    public VideoProbe probe(DownloadRequest request, DownloadSource source) {
        var formatSelector = resolveFormatSelector(source.getFormatSelector(), request.qualidade());
        var outputDir = workDir(request);
        var infoJson = outputDir.resolve(INFO_JSON);
        var cmd = new ArrayList<String>();
        cmd.add(properties.ytdlpPath());
        cmd.add("--no-playlist");
        cmd.add("-f");
        cmd.add(formatSelector);
        cmd.add("--dump-single-json");
        addExtraArgs(cmd, source.getExtraArgs());
        cmd.add("--");
        cmd.add(request.url());

        try {
            Files.createDirectories(outputDir);
            var result = ProcessRunner.runWithStdoutTo(cmd, infoJson, timeout(), MAX_LOG_LINES);
            if (!result.succeeded() || Files.size(infoJson) == 0) {
                Files.deleteIfExists(infoJson);
                var failure = classify("yt-dlp probe exited with code " + result.exitCode(), result.output());
                if (failure instanceof ContentUnavailableException unavailable) throw unavailable;
                log.warn("yt-dlp probe failed, proceeding with download: correlationId={} exit={}",
                        request.correlationId(), result.exitCode());
                return VideoProbe.unknown();
            }
            JsonNode info;
            try (var in = Files.newInputStream(infoJson)) {
                info = JSON.readTree(in);
            }
            long fileSize = estimateSize(info);
            double duration = number(info.path("duration"));
            log.info("yt-dlp probe: correlationId={} fileSizeBytes={} durationSeconds={}",
                    request.correlationId(), fileSize, (int) duration);
            return new VideoProbe(fileSize, duration, infoJson);
        } catch (ContentUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("yt-dlp probe interrupted", e);
        } catch (Exception e) {
            log.warn("yt-dlp probe failed, proceeding with download: correlationId={} error={}",
                    request.correlationId(), e.getMessage());
            try {
                Files.deleteIfExists(infoJson);
            } catch (IOException ignored) {
            }
            return VideoProbe.unknown();
        }
    }

    static long estimateSize(JsonNode info) {
        long direct = (long) number(info.path("filesize"));
        if (direct > 0) return direct;
        long approx = (long) number(info.path("filesize_approx"));
        if (approx > 0) return approx;
        long sum = 0;
        var formats = info.path("requested_formats");
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            long size = (long) number(format.path("filesize"));
            if (size <= 0) size = (long) number(format.path("filesize_approx"));
            if (size <= 0) return 0;
            sum += size;
        }
        return sum;
    }

    static DownloadException classify(String message, String output) {
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        for (String marker : UNAVAILABLE_MARKERS) {
            if (lower.contains(marker)) return new ContentUnavailableException(message + ": " + marker);
        }
        return new DownloadException(message + ": " + output);
    }

    private static double number(JsonNode node) {
        return node != null && node.isNumber() ? node.doubleValue() : 0;
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, properties.processingTimeoutSeconds()));
    }

    private Path workDir(DownloadRequest request) {
        return Path.of(properties.tmpDir(), request.correlationId());
    }

    private static void addExtraArgs(List<String> cmd, String[] extraArgs) {
        if (extraArgs == null) return;
        for (String arg : extraArgs) {
            if (!MANAGED_FLAGS.contains(arg)) cmd.add(arg);
        }
    }

    private DownloadResult ytdlpFallback(DownloadRequest request, DownloadSource source, VideoProbe probe, Throwable t) {
        if (t instanceof DownloadException downloadException) throw downloadException;
        log.error("yt-dlp circuit breaker open or bulkhead full: correlationId={}", request.correlationId(), t);
        throw new DownloadException("yt-dlp service temporarily unavailable", t);
    }

    private String resolveFormatSelector(String tableSelector, String qualidade) {
        return switch (qualidade) {
            case "1080p" -> "bestvideo[height<=1080][ext=mp4]+bestaudio/best[height<=1080]";
            case "720p"  -> "bestvideo[height<=720][ext=mp4]+bestaudio/best[height<=720]";
            case "480p"  -> "bestvideo[height<=480][ext=mp4]+bestaudio/best[height<=480]";
            case "360p"  -> "bestvideo[height<=360][ext=mp4]+bestaudio/best[height<=360]";
            default      -> tableSelector;
        };
    }

    static Path findDownloadedFile(Path outputDir) throws IOException {
        try (var stream = Files.list(outputDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> isMediaCandidate(p.getFileName().toString()))
                    .max(Comparator.comparingLong(YtDlpService::sizeOrZero))
                    .orElseThrow(() -> new DownloadException("No file found after yt-dlp execution"));
        }
    }

    private static boolean isMediaCandidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.equals(INFO_JSON)
                && !lower.endsWith(".part")
                && !lower.endsWith(".ytdl")
                && !lower.endsWith(".temp")
                && !lower.endsWith(".info.json")
                && !lower.contains(".part-frag");
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
