package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private static final long MIN_VIDEO_BITRATE_BPS = 150_000;
    private static final long AUDIO_BITRATE_BPS     = 128_000;

    private final DownloaderProperties properties;

    public FfmpegService(DownloaderProperties properties) {
        this.properties = properties;
    }

    @CircuitBreaker(name = "ffmpeg", fallbackMethod = "ffmpegFallback")
    @Bulkhead(name = "ffmpegBulkhead")
    public Path encode(Path input, String correlationId) throws DownloadException {
        var output = input.resolveSibling("encoded_" + input.getFileName());

        log.info("Starting ffmpeg encode: correlationId={} input={}", correlationId, input);
        Span.current().setAttribute("ffmpeg.input", input.toString());

        long start = System.currentTimeMillis();
        try {
            var process = new ProcessBuilder(List.of(
                    properties.ffmpegPath(),
                    "-i", input.toString(),
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-movflags", "+faststart",
                    output.toString()
            )).redirectErrorStream(true).start();

            String outputLog;
            int exitCode;
            try {
                outputLog = new String(process.getInputStream().readAllBytes());
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new DownloadException("ffmpeg interrupted", e);
            }

            if (exitCode != 0) {
                log.error("ffmpeg failed (exit {}): correlationId={}\n{}", exitCode, correlationId, outputLog);
                throw new DownloadException("ffmpeg exited with code " + exitCode);
            }

            log.info("ffmpeg encode completed in {}ms: correlationId={}", System.currentTimeMillis() - start, correlationId);
            return output;

        } catch (IOException e) {
            throw new DownloadException("ffmpeg execution error", e);
        }
    }

    @CircuitBreaker(name = "ffmpeg", fallbackMethod = "ffmpegSizeFallback")
    @Bulkhead(name = "ffmpegBulkhead")
    public Path encodeToSize(Path input, String correlationId, long maxBytes) throws DownloadException {
        double duration = getDurationSeconds(input);
        if (duration <= 0) throw new DownloadException("Could not determine video duration");

        long targetBits    = (long) (maxBytes * 0.92 * 8); // 8% de margem
        long videoBitrate  = (targetBits / (long) duration) - AUDIO_BITRATE_BPS;

        log.info("Size compression: correlationId={} duration={}s videoBitrate={}kbps",
                correlationId, (int) duration, videoBitrate / 1000);

        if (videoBitrate < MIN_VIDEO_BITRATE_BPS) {
            log.warn("Calculated bitrate {}kbps below minimum — video too long to compress acceptably",
                    videoBitrate / 1000);
            return null;
        }

        var output = input.resolveSibling("compressed_" + input.getFileName());
        long start = System.currentTimeMillis();

        try {
            var process = new ProcessBuilder(List.of(
                    properties.ffmpegPath(),
                    "-i", input.toString(),
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-b:v",     (videoBitrate / 1000) + "k",
                    "-maxrate", (videoBitrate * 2 / 1000) + "k",
                    "-bufsize",  (videoBitrate * 4 / 1000) + "k",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    output.toString()
            )).redirectErrorStream(true).start();

            String outputLog;
            int exitCode;
            try {
                outputLog = new String(process.getInputStream().readAllBytes());
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new DownloadException("ffmpeg size-encode interrupted", e);
            }

            if (exitCode != 0) {
                log.error("ffmpeg size-encode failed (exit {}): correlationId={}\n{}", exitCode, correlationId, outputLog);
                throw new DownloadException("ffmpeg size-encode exited with code " + exitCode);
            }

            long resultSize = Files.size(output);
            log.info("ffmpeg size-encode done in {}ms: correlationId={} resultSize={}MB",
                    System.currentTimeMillis() - start, correlationId, resultSize / 1_048_576);
            return output;

        } catch (IOException e) {
            throw new DownloadException("ffmpeg size-encode execution error", e);
        }
    }

    public boolean canFitInSize(double durationSeconds, long maxBytes) {
        if (durationSeconds <= 0) return true;
        long targetBits   = (long) (maxBytes * 0.92 * 8);
        long videoBitrate = (targetBits / (long) durationSeconds) - AUDIO_BITRATE_BPS;
        return videoBitrate >= MIN_VIDEO_BITRATE_BPS;
    }

    public boolean needsEncode(Path filePath, String qualidade) {
        var filename = filePath.getFileName().toString().toLowerCase();
        boolean isH264 = filename.endsWith(".mp4");
        boolean qualityRequested = !"original".equals(qualidade);
        return !isH264 || qualityRequested;
    }

    private double getDurationSeconds(Path input) {
        var ffprobePath = properties.ffmpegPath().replace("ffmpeg", "ffprobe");
        try {
            var process = new ProcessBuilder(List.of(
                    ffprobePath,
                    "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    input.toString()
            )).redirectErrorStream(false).start();

            var out = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return out.isBlank() ? -1 : Double.parseDouble(out);
        } catch (Exception e) {
            log.warn("ffprobe failed for {}: {}", input, e.getMessage());
            return -1;
        }
    }

    private Path ffmpegFallback(Path input, String correlationId, Throwable t) {
        log.error("ffmpeg circuit breaker open: correlationId={}", correlationId, t);
        throw new DownloadException("ffmpeg service temporarily unavailable", t);
    }

    private Path ffmpegSizeFallback(Path input, String correlationId, long maxBytes, Throwable t) {
        log.error("ffmpeg size-encode circuit breaker open: correlationId={}", correlationId, t);
        throw new DownloadException("ffmpeg service temporarily unavailable", t);
    }
}
