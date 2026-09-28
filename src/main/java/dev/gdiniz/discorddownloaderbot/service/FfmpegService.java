package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.util.ProcessRunner;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);
    private static final int MAX_LOG_LINES = 50;

    private static final long MIN_VIDEO_BITRATE_BPS = 150_000;
    private static final long AUDIO_BITRATE_BPS     = 128_000;
    private static final Duration PROBE_TIMEOUT     = Duration.ofSeconds(15);

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif", "heic", "avif");
    private static final Set<String> DISCORD_AUDIO_CODECS = Set.of("aac", "mp3");

    public record MediaInfo(String videoCodec, String audioCodec) {
        public boolean hasVideo() {
            return videoCodec != null;
        }
    }

    private final DownloaderProperties properties;

    public FfmpegService(DownloaderProperties properties) {
        this.properties = properties;
    }

    @CircuitBreaker(name = "ffmpeg", fallbackMethod = "ffmpegFallback")
    @Bulkhead(name = "ffmpegBulkhead")
    public Path encode(Path input, String correlationId) throws DownloadException {
        var output = input.resolveSibling("encoded_" + baseName(input) + ".mp4");

        log.info("Starting ffmpeg encode: correlationId={} input={}", correlationId, input);
        Span.current().setAttribute("ffmpeg.input", input.toString());

        long start = System.currentTimeMillis();
        runFfmpeg(List.of(
                properties.ffmpegPath(),
                "-y",
                "-nostats",
                "-loglevel", "error",
                "-threads", String.valueOf(properties.ffmpegThreads()),
                "-i", input.toString(),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-movflags", "+faststart",
                output.toString()
        ), "ffmpeg", correlationId);

        log.info("ffmpeg encode completed in {}ms: correlationId={}", System.currentTimeMillis() - start, correlationId);
        return output;
    }

    @CircuitBreaker(name = "ffmpeg", fallbackMethod = "ffmpegSizeFallback")
    @Bulkhead(name = "ffmpegBulkhead")
    public Path encodeToSize(Path input, String correlationId, long maxBytes) throws DownloadException {
        double duration = getDurationSeconds(input);
        if (duration <= 0) throw new DownloadException("Could not determine video duration");

        long videoBitrate = targetVideoBitrate(duration, maxBytes);

        log.info("Size compression: correlationId={} duration={}s videoBitrate={}kbps",
                correlationId, (int) duration, videoBitrate / 1000);

        if (videoBitrate < MIN_VIDEO_BITRATE_BPS) {
            log.warn("Calculated bitrate {}kbps below minimum — video too long to compress acceptably",
                    videoBitrate / 1000);
            return null;
        }

        var output = input.resolveSibling("compressed_" + baseName(input) + ".mp4");
        long start = System.currentTimeMillis();

        runFfmpeg(List.of(
                properties.ffmpegPath(),
                "-y",
                "-nostats",
                "-loglevel", "error",
                "-threads", String.valueOf(properties.ffmpegThreads()),
                "-i", input.toString(),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-b:v",     (videoBitrate / 1000) + "k",
                "-maxrate", (videoBitrate * 3 / 2 / 1000) + "k",
                "-bufsize",  (videoBitrate * 2 / 1000) + "k",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                output.toString()
        ), "ffmpeg size-encode", correlationId);

        try {
            long resultSize = Files.size(output);
            log.info("ffmpeg size-encode done in {}ms: correlationId={} resultSize={}MB",
                    System.currentTimeMillis() - start, correlationId, resultSize / 1_048_576);
        } catch (IOException e) {
            throw new DownloadException("ffmpeg size-encode produced no output", e);
        }
        return output;
    }

    public boolean canFitInSize(double durationSeconds, long maxBytes) {
        if (durationSeconds <= 0) return true;
        return targetVideoBitrate(durationSeconds, maxBytes) >= MIN_VIDEO_BITRATE_BPS;
    }

    public boolean needsEncode(Path filePath, String qualidade) {
        String ext = extension(filePath);
        if (IMAGE_EXTENSIONS.contains(ext)) return false;
        if (!"mp4".equals(ext)) return true;

        var info = probeCodecs(filePath);
        if (info == null) return !"original".equals(qualidade);
        if (!info.hasVideo()) return false;
        boolean videoOk = "h264".equals(info.videoCodec());
        boolean audioOk = info.audioCodec() == null || DISCORD_AUDIO_CODECS.contains(info.audioCodec());
        return !(videoOk && audioOk);
    }

    public boolean isImage(Path filePath) {
        return IMAGE_EXTENSIONS.contains(extension(filePath));
    }

    MediaInfo probeCodecs(Path input) {
        try {
            var result = ProcessRunner.run(List.of(
                    ffprobePath(),
                    "-v", "error",
                    "-show_entries", "stream=codec_type,codec_name",
                    "-of", "csv=p=0",
                    input.toString()
            ), PROBE_TIMEOUT, 20, false);
            if (!result.succeeded()) return null;
            String video = null;
            String audio = null;
            for (String line : result.output().split("\n")) {
                var parts = line.strip().split(",");
                if (parts.length < 2) continue;
                String name = parts[0].strip().toLowerCase(Locale.ROOT);
                String type = parts[1].strip().toLowerCase(Locale.ROOT);
                if ("video".equals(type) && video == null && !"mjpeg".equals(name) && !"png".equals(name)) video = name;
                if ("audio".equals(type) && audio == null) audio = name;
            }
            return new MediaInfo(video, audio);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("ffprobe interrupted", e);
        } catch (Exception e) {
            log.warn("ffprobe codec detection failed for {}: {}", input, e.getMessage());
            return null;
        }
    }

    private void runFfmpeg(List<String> command, String label, String correlationId) {
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(command,
                    Duration.ofSeconds(Math.max(1, properties.processingTimeoutSeconds())), MAX_LOG_LINES, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException(label + " interrupted", e);
        } catch (TimeoutException e) {
            throw new DownloadInterruptedException(label + " timed out", e);
        } catch (IOException e) {
            throw new DownloadException(label + " execution error", e);
        }
        if (!result.succeeded()) {
            log.error("{} failed (exit {}): correlationId={}\n{}", label, result.exitCode(), correlationId, result.output());
            throw new DownloadException(label + " exited with code " + result.exitCode() + ": " + result.output());
        }
    }

    private static long targetVideoBitrate(double durationSeconds, long maxBytes) {
        long targetBits = (long) (maxBytes * 0.92 * 8);
        return (long) (targetBits / Math.max(1.0, durationSeconds)) - AUDIO_BITRATE_BPS;
    }

    private double getDurationSeconds(Path input) {
        try {
            var result = ProcessRunner.run(List.of(
                    ffprobePath(),
                    "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    input.toString()
            ), PROBE_TIMEOUT, 5, false);
            var out = result.output().strip();
            return out.isBlank() ? -1 : Double.parseDouble(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("ffprobe interrupted", e);
        } catch (Exception e) {
            log.warn("ffprobe failed for {}: {}", input, e.getMessage());
            return -1;
        }
    }

    private String ffprobePath() {
        var ffmpeg = Path.of(properties.ffmpegPath());
        var name = ffmpeg.getFileName().toString().replace("ffmpeg", "ffprobe");
        return ffmpeg.getParent() == null ? name : ffmpeg.resolveSibling(name).toString();
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static String baseName(Path path) {
        var name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Path ffmpegFallback(Path input, String correlationId, Throwable t) {
        if (t instanceof DownloadException downloadException) throw downloadException;
        log.error("ffmpeg circuit breaker open or bulkhead full: correlationId={}", correlationId, t);
        throw new DownloadException("ffmpeg service temporarily unavailable", t);
    }

    private Path ffmpegSizeFallback(Path input, String correlationId, long maxBytes, Throwable t) {
        if (t instanceof DownloadException downloadException) throw downloadException;
        log.error("ffmpeg size-encode circuit breaker open or bulkhead full: correlationId={}", correlationId, t);
        throw new DownloadException("ffmpeg service temporarily unavailable", t);
    }
}
