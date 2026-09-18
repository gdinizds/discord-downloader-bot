package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.VideoProbe;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class YtDlpService {

    private static final Logger log = LoggerFactory.getLogger(YtDlpService.class);

    private final DownloaderProperties properties;

    public YtDlpService(DownloaderProperties properties) {
        this.properties = properties;
    }

    @CircuitBreaker(name = "ytdlp", fallbackMethod = "ytdlpFallback")
    @Retry(name = "ytdlpRetry")
    @Bulkhead(name = "ytdlpBulkhead")
    public DownloadResult execute(DownloadRequest request, DownloadSource source) throws DownloadException {
        if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new DownloadException("yt-dlp skipped — processing timed out");
        }
        var outputDir = Path.of(properties.tmpDir(), request.correlationId());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new DownloadException("Failed to create temp directory", e);
        }

        var formatSelector = resolveFormatSelector(source.getFormatSelector(), request.qualidade());
        var outputTemplate = outputDir.resolve("%(title)s.%(ext)s").toString();

        var command = buildCommand(request.url(), formatSelector, source.getExtraArgs(), outputTemplate);

        log.info("Executing yt-dlp: correlationId={} url={}", request.correlationId(), request.url());

        var span = Span.current();
        span.setAttribute("download.sourceHost", request.url());
        span.setAttribute("download.qualidade", request.qualidade());

        long start = System.currentTimeMillis();
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            int exitCode;
            try {
                output = new String(process.getInputStream().readAllBytes());
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new DownloadException("yt-dlp interrupted", e);
            }

            if (exitCode != 0) {
                log.error("yt-dlp failed (exit {}): correlationId={}\n{}", exitCode, request.correlationId(), output);
                throw new DownloadException("yt-dlp exited with code " + exitCode + ": " + output);
            }

            var downloadedFile = findDownloadedFile(outputDir);
            long size = Files.size(downloadedFile);
            String title = downloadedFile.getFileName().toString();
            int dotIdx = title.lastIndexOf('.');
            String ext = dotIdx >= 0 ? title.substring(dotIdx + 1) : "mp4";
            String name = dotIdx >= 0 ? title.substring(0, dotIdx) : title;

            log.info("yt-dlp completed in {}ms: correlationId={} size={}",
                    System.currentTimeMillis() - start, request.correlationId(), size);

            return new DownloadResult(downloadedFile, name, size, ext);

        } catch (IOException e) {
            throw new DownloadException("yt-dlp execution error", e);
        }
    }

    public VideoProbe probe(DownloadRequest request, DownloadSource source) {
        var formatSelector = resolveFormatSelector(source.getFormatSelector(), request.qualidade());
        var cmd = new ArrayList<String>();
        cmd.add(properties.ytdlpPath());
        cmd.add("-f"); cmd.add(formatSelector);
        cmd.add("--print"); cmd.add("%(filesize)s");
        cmd.add("--print"); cmd.add("%(filesize_approx)s");
        cmd.add("--print"); cmd.add("%(duration)s");
        if (source.getExtraArgs() != null) {
            cmd.addAll(List.of(source.getExtraArgs()));
        }
        cmd.add(request.url());

        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output;
            try {
                output = new String(process.getInputStream().readAllBytes()).trim();
                process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new DownloadException("probe interrupted", e);
            }

            var lines = output.split("\n");
            long fileSize = parseSize(lines.length > 0 ? lines[0].trim() : "");
            if (fileSize <= 0) fileSize = parseSize(lines.length > 1 ? lines[1].trim() : "");
            double duration = lines.length > 2 ? parseDouble(lines[2].trim()) : 0;

            log.info("yt-dlp probe: correlationId={} fileSizeBytes={} durationSeconds={}",
                    request.correlationId(), fileSize, (int) duration);
            return new VideoProbe(fileSize, duration);

        } catch (Exception e) {
            log.warn("yt-dlp probe failed, proceeding with download: correlationId={} error={}",
                    request.correlationId(), e.getMessage());
            return new VideoProbe(0, 0);
        }
    }

    private long parseSize(String s) {
        if (s == null || s.isBlank() || "NA".equalsIgnoreCase(s) || "None".equalsIgnoreCase(s)) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private double parseDouble(String s) {
        if (s == null || s.isBlank() || "NA".equalsIgnoreCase(s) || "None".equalsIgnoreCase(s)) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private DownloadResult ytdlpFallback(DownloadRequest request, DownloadSource source, Throwable t) {
        log.error("yt-dlp circuit breaker open or retries exhausted: correlationId={}", request.correlationId(), t);
        throw new DownloadException("yt-dlp service temporarily unavailable", t);
    }

    private List<String> buildCommand(String url, String formatSelector, String[] extraArgs, String outputTemplate) {
        var cmd = new ArrayList<String>();
        cmd.add(properties.ytdlpPath());
        cmd.add("-f");
        cmd.add(formatSelector);
        cmd.add("-o");
        cmd.add(outputTemplate);
        if (extraArgs != null) {
            for (String arg : extraArgs) cmd.add(arg);
        }
        cmd.add(url);
        return cmd;
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

    private Path findDownloadedFile(Path outputDir) throws IOException {
        try (var stream = Files.list(outputDir)) {
            return stream.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new DownloadException("No file found after yt-dlp execution"));
        }
    }
}
