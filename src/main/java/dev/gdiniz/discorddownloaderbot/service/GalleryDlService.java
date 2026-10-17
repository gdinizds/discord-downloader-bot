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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
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
    private static final Set<String> MANAGED_FLAGS = Set.of("--no-playlist", "--no-progress");

    private static final List<String> UNAVAILABLE_MARKERS = List.of(
            "video unavailable",
            "private video",
            "this video is private",
            "this account is private",
            "this tweet is from a private account",
            "has been removed",
            "this post has been deleted",
            "no longer available",
            "http error 404",
            "http error 410",
            "tweet not found",
            "post not found",
            "not found",
            "does not exist",
            "private account",
            "post deleted",
            "content deleted",
            "user has deleted this post",
            "no video formats found",
            "no video could be found",
            "there is no video in this post",
            "there is no video in this tweet",
            "unsupported url",
            "is not a valid url",
            "no media found"
    );

    private static final List<String> BLOCKED_MARKERS = List.of(
            "rate-limit reached",
            "rate limit reached",
            "rate-limit",
            "rate limit",
            "login required",
            "log in to",
            "sign in to confirm",
            "http error 429",
            "http error 401",
            "http error 403",
            "too many requests",
            "checkpoint_required",
            "checkpoint required",
            "challenge required",
            "cookies are no longer valid",
            "account has been flagged"
    );

    private final DownloaderProperties properties;

    public GalleryDlService(DownloaderProperties properties) {
        this.properties = properties;
    }

    public DownloadResult execute(DownloadRequest request, DownloadSource source) throws DownloadException {
        return execute(request, source, properties.discordMaxFileBytes());
    }

    @CircuitBreaker(name = "gallerydl", fallbackMethod = "galleryDlFallback")
    @Retry(name = "gallerydlRetry")
    @Bulkhead(name = "gallerydlBulkhead")
    public DownloadResult execute(DownloadRequest request, DownloadSource source, long maxBytes) throws DownloadException {
        if (Thread.currentThread().isInterrupted()) {
            throw new DownloadInterruptedException("gallery-dl skipped — processing timed out", null);
        }

        Path outputDir = Path.of(properties.tmpDir(), request.correlationId());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new DownloadException("Failed to create download directory", e);
        }

        String targetUrl = resolveTargetUrl(request.url(), source);
        var cmd = buildGalleryDlCommand(request, source, maxBytes, outputDir, targetUrl);

        log.info("Starting unified gallery-dl download: correlationId={} url={} target={}",
                request.correlationId(), request.url(), targetUrl);
        Span.current().setAttribute("gallerydl.url", request.url());

        long start = System.currentTimeMillis();
        ProcessRunner.Result result = runProcess(cmd);

        var files = findFilesSafe(outputDir);
        if (!files.isEmpty()) {
            return buildResult(outputDir, files, request, start);
        }

        // If native extractor produced no files and URL wasn't already ytdl-prefixed, retry with ytdl:
        if (!targetUrl.startsWith("ytdl:")) {
            log.info("gallery-dl native extraction returned no files, retrying with ytdl backend: correlationId={} url={}",
                    request.correlationId(), request.url());
            String fallbackTarget = "ytdl:" + request.url();
            var fallbackCmd = buildGalleryDlCommand(request, source, maxBytes, outputDir, fallbackTarget);
            result = runProcess(fallbackCmd);
            files = findFilesSafe(outputDir);
            if (!files.isEmpty()) {
                return buildResult(outputDir, files, request, start);
            }
        }

        if (!result.succeeded()) {
            log.error("gallery-dl failed (exit {}): correlationId={}\n{}",
                    result.exitCode(), request.correlationId(), result.output());
            throw classify("gallery-dl exited with code " + result.exitCode(), result.output());
        }

        throw new DownloadException("No files downloaded by gallery-dl");
    }

    List<String> buildGalleryDlCommand(DownloadRequest request, DownloadSource source, long maxBytes, Path outputDir, String targetUrl) {
        var cmd = new ArrayList<String>();
        cmd.add(properties.galleryDlPath());
        cmd.add("-d");
        cmd.add(outputDir.toString());
        cmd.add("-D");
        cmd.add("");
        cmd.add("--range");
        cmd.add("1-10");
        cmd.add("--write-metadata");
        cmd.add("--write-info-json");

        // Enable unified ytdl (yt-dlp) integration
        cmd.add("-o");
        cmd.add("extractor.ytdl.enabled=true");
        cmd.add("-o");
        cmd.add("extractor.ytdl.module=yt_dlp");
        cmd.add("-o");
        cmd.add("downloader.ytdl.forward-cookies=true");

        // Format selector
        String formatSelector = resolveFormatSelector(source != null ? source.getFormatSelector() : null, request.qualidade());
        if (formatSelector != null && !formatSelector.isBlank()) {
            cmd.add("-o");
            cmd.add("extractor.ytdl.format=" + formatSelector);
        }

        // yt-dlp raw-options for giant video protection and clean outputs (primitives only, match_filter is passed via cmdline-args)
        long maxFileSize = Math.max(104857600L, maxBytes * 3); // cap at ~75MB-100MB minimum
        cmd.add("-o");
        cmd.add("extractor.ytdl.raw-options={\"noplaylist\":true,\"merge_output_format\":\"mp4\",\"max_filesize\":"
                + maxFileSize + "}");

        // Forward duration filter and extra yt-dlp arguments from source via cmdline-args
        List<String> ytdlExtra = new ArrayList<>();
        ytdlExtra.add("--match-filter");
        ytdlExtra.add("duration <=? 3600");
        ytdlExtra.addAll(extractYtdlExtraArgs(source != null ? source.getExtraArgs() : null));
        try {
            cmd.add("-o");
            cmd.add("extractor.ytdl.cmdline-args=" + JSON.writeValueAsString(ytdlExtra));
        } catch (Exception e) {
            log.warn("Could not serialize ytdl cmdline-args: {}", e.getMessage());
        }

        addCookiesIfPresent(cmd, source != null ? source.getExtraArgs() : null, outputDir);

        cmd.add("--");
        cmd.add(targetUrl);
        return cmd;
    }

    private ProcessRunner.Result runProcess(List<String> cmd) {
        try {
            return ProcessRunner.run(cmd, timeout(), MAX_LOG_LINES, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadInterruptedException("gallery-dl execution interrupted", e);
        } catch (TimeoutException e) {
            throw new DownloadInterruptedException("gallery-dl timed out", e);
        } catch (IOException e) {
            throw new DownloadException("gallery-dl process execution failed", e);
        }
    }

    private static List<Path> findFilesSafe(Path outputDir) {
        try {
            return findDownloadedFiles(outputDir);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static DownloadResult buildResult(Path outputDir, List<Path> files, DownloadRequest request, long start) {
        var primaryFile = files.stream()
                .max(Comparator.comparingLong(GalleryDlService::sizeOrZero))
                .orElse(files.getFirst());
        long totalSize = files.stream().mapToLong(GalleryDlService::sizeOrZero).sum();
        String fileName = primaryFile.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        String ext = dotIdx >= 0 ? fileName.substring(dotIdx + 1) : "mp4";
        String fallbackName = dotIdx >= 0 ? fileName.substring(0, dotIdx) : fileName;

        String title = extractTitleFromMetadata(outputDir, primaryFile);
        if (title == null || title.isBlank()) {
            title = fallbackName;
        }

        log.info("gallery-dl completed in {}ms: correlationId={} files={} totalSize={} title={}",
                System.currentTimeMillis() - start, request.correlationId(), files.size(), totalSize, title);
        return new DownloadResult(primaryFile, title, totalSize, ext, files);
    }

    static boolean isYtdlHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return h.equals("youtube.com") || h.endsWith(".youtube.com")
                || h.equals("youtu.be")
                || h.equals("tiktok.com") || h.endsWith(".tiktok.com");
    }

    static String resolveTargetUrl(String url, DownloadSource source) {
        if (url == null) return "";
        if (url.startsWith("ytdl:")) return url;

        String host = null;
        if (source != null && source.getHost() != null) {
            host = source.getHost();
        }
        if (host == null) {
            try {
                var uri = URI.create(url);
                host = uri.getHost();
            } catch (Exception ignored) {}
        }

        if (isYtdlHost(host)) {
            return "ytdl:" + url;
        }
        return url;
    }

    public static String resolveFormatSelector(String tableSelector, String qualidade) {
        return switch (qualidade) {
            case "1080p" -> h264Selector(1080);
            case "720p"  -> h264Selector(720);
            case "480p"  -> h264Selector(480);
            case "360p"  -> h264Selector(360);
            default      -> (tableSelector != null && !tableSelector.isBlank())
                    ? tableSelector
                    : DownloadSource.GENERIC_FORMAT_SELECTOR;
        };
    }

    public static String h264Selector(int height) {
        return ("bestvideo[vcodec^=avc1][height<=%1$d]+bestaudio[ext=m4a]"
                + "/best[vcodec^=avc1][height<=%1$d]"
                + "/bestvideo[height<=%1$d][ext=mp4]+bestaudio"
                + "/best[height<=%1$d]"
                + "/bestaudio"
                + "/best").formatted(height);
    }

    static List<String> extractYtdlExtraArgs(String[] extraArgs) {
        if (extraArgs == null || extraArgs.length == 0) return List.of();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < extraArgs.length; i++) {
            String arg = extraArgs[i];
            if (MANAGED_FLAGS.contains(arg)) continue;
            if (COOKIE_FLAGS.contains(arg)) {
                i++; // skip cookie path
                continue;
            }
            list.add(arg);
        }
        return list;
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

    static boolean isMediaCandidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.equals("probe.info.json")
                && !lower.endsWith(".part")
                && !lower.endsWith(".ytdl")
                && !lower.endsWith(".temp")
                && !lower.endsWith(".tmp")
                && !lower.endsWith(".info.json")
                && !lower.endsWith(".json")
                && !lower.endsWith(".txt")
                && !lower.endsWith(".aria2")
                && !lower.contains(".part-frag");
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
                Path copy = privateCookieCopy(original, workDir);
                cmd.add("--cookies");
                cmd.add(copy.toString());
                return;
            }
        }
    }

    static Path privateCookieCopy(Path shared, Path workDir) {
        var target = workDir.resolve(".auth").resolve(shared.getFileName());
        if (Files.exists(target)) return target;
        if (!Files.isRegularFile(shared)) {
            log.warn("Cookie file not found, passing it through unchanged: {}", shared);
            return shared;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(shared, target, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}
            return target;
        } catch (IOException e) {
            throw new DownloadException("Failed to prepare private cookie file", e);
        }
    }

    static DownloadException classify(String message, String output) {
        String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        for (String marker : UNAVAILABLE_MARKERS) {
            if (lower.contains(marker)) {
                return new ContentUnavailableException(message + ": " + marker);
            }
        }
        for (String marker : BLOCKED_MARKERS) {
            if (lower.contains(marker)) {
                return new SourceBlockedException(message + ": " + marker);
            }
        }
        return new DownloadException(message + ": " + output);
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, properties.processingTimeoutSeconds()));
    }

    private DownloadResult galleryDlFallback(DownloadRequest request, DownloadSource source, long maxBytes, Throwable t) {
        if (t instanceof DownloadException downloadException) throw downloadException;
        log.error("gallery-dl circuit breaker open or bulkhead full: correlationId={}", request.correlationId(), t);
        throw new DownloadException("gallery-dl service temporarily unavailable", t);
    }
}
