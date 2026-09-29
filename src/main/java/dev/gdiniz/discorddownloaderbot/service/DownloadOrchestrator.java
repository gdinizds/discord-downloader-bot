package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJob;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJobRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSourceRepository;
import dev.gdiniz.discorddownloaderbot.domain.GuildConfig;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.SourceBlockedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.GatewayResponse;
import dev.gdiniz.discorddownloaderbot.kafka.ResponseProducer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DownloadOrchestrator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DownloadOrchestrator.class);
    private static final long SOURCE_CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();

    static final String MSG_IN_PROGRESS = "⏳ Download em andamento...";
    static final String MSG_INVALID_URL = "❌ Link inválido. Envie um link público começando com http:// ou https://.";
    static final String MSG_UNAVAILABLE = "❌ O conteúdo não está disponível (privado, removido ou link não suportado).";
    static final String MSG_BLOCKED = "❌ A plataforma limitou o acesso no momento (rate limit ou login exigido). Tente novamente mais tarde.";
    static final String MSG_GENERIC_FAILURE = "❌ Não foi possível baixar o conteúdo. Verifique se o link é válido e tente novamente.";
    static final String MSG_UNEXPECTED = "❌ Ocorreu um erro inesperado ao processar o download. Tente novamente em instantes.";

    private record CachedSource(Optional<DownloadSource> source, long loadedAt) {}

    private final YtDlpService ytDlpService;
    private final FfmpegService ffmpegService;
    private final S3UploadService s3UploadService;
    private final DownloadSourceRepository sourceRepository;
    private final DownloadJobRepository jobRepository;
    private final GuildConfigCache guildConfigCache;
    private final ResponseProducer responseProducer;
    private final DownloaderProperties properties;
    private final MeterRegistry meterRegistry;
    private final UrlPolicy urlPolicy;
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("download-timeout-", 0).factory());

    private final Map<String, CachedSource> sourceCache = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object drainLock = new Object();

    public DownloadOrchestrator(YtDlpService ytDlpService, FfmpegService ffmpegService,
                                S3UploadService s3UploadService, DownloadSourceRepository sourceRepository,
                                DownloadJobRepository jobRepository, GuildConfigCache guildConfigCache,
                                ResponseProducer responseProducer,
                                DownloaderProperties properties, MeterRegistry meterRegistry,
                                UrlPolicy urlPolicy) {
        this.ytDlpService = ytDlpService;
        this.ffmpegService = ffmpegService;
        this.s3UploadService = s3UploadService;
        this.sourceRepository = sourceRepository;
        this.jobRepository = jobRepository;
        this.guildConfigCache = guildConfigCache;
        this.responseProducer = responseProducer;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.urlPolicy = urlPolicy;
        meterRegistry.gauge("downloader.job.in_flight", inFlight);
    }

    @PostConstruct
    public void init() {
        cleanOrphanedTempDirs();
    }

    @Override
    public void destroy() {
        long deadline = System.currentTimeMillis()
                + TimeUnit.SECONDS.toMillis(properties.processingTimeoutSeconds() + 5L);
        synchronized (drainLock) {
            while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
                log.info("Waiting for {} in-flight download(s) before shutdown", inFlight.get());
                try {
                    drainLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        timeoutScheduler.shutdownNow();
    }

    int inFlight() {
        return inFlight.get();
    }

    public void process(DownloadRequest request) {
        inFlight.incrementAndGet();
        try {
            doProcess(request);
        } finally {
            if (inFlight.decrementAndGet() == 0) {
                synchronized (drainLock) {
                    drainLock.notifyAll();
                }
            }
        }
    }

    private void doProcess(DownloadRequest request) {
        var host = extractHost(request.url());
        boolean isInteraction = request.interactionToken() != null;

        var rejection = urlPolicy.rejectionReason(request.url());
        if (rejection.isPresent()) {
            log.warn("Rejected download url: correlationId={} reason={}", request.correlationId(), rejection.get());
            meterRegistry.counter("downloader.job.rejected", "reason", "invalid_url").increment();
            responseProducer.send(buildReply(request, isInteraction, false, MSG_INVALID_URL, null, null, true));
            return;
        }

        if (alreadyProcessed(request.correlationId())) {
            log.info("Duplicate download event ignored: correlationId={}", request.correlationId());
            meterRegistry.counter("downloader.job.duplicate").increment();
            return;
        }

        var span = Span.current();
        span.setAttribute("discord.correlationId", request.correlationId());
        span.setAttribute("discord.guildId", request.guildId() != null ? request.guildId() : "");
        span.setAttribute("download.sourceHost", host);
        span.setAttribute("download.qualidade", request.qualidade());

        meterRegistry.counter("downloader.job.started",
                "source_host", host, "qualidade", request.qualidade()).increment();

        long startTime = System.currentTimeMillis();
        Path workDir = Path.of(properties.tmpDir(), request.correlationId());

        var replied  = new AtomicBoolean(false);
        var announced = new AtomicBoolean(false);
        var timedOut = new AtomicBoolean(false);
        var worker   = Thread.currentThread();
        var timeoutTask = timeoutScheduler.schedule(() -> {
            if (timedOut.compareAndSet(false, true)) {
                log.warn("Processing timed out after {}s: correlationId={}",
                        properties.processingTimeoutSeconds(), request.correlationId());
                worker.interrupt();
            }
        }, properties.processingTimeoutSeconds(), TimeUnit.SECONDS);

        DownloadJob job = null;
        try {
            long maxFileBytes = guildConfigCache.get(request.guildId())
                    .map(GuildConfig::getMaxFileSizeBytes)
                    .orElse(properties.discordMaxFileBytes());

            job = jobRepository.save(DownloadJob.create(request.correlationId(), request.guildId(), request.userId(),
                    request.url(), host, request.qualidade()));

            responseProducer.send(isInteraction
                    ? GatewayResponse.deferredReply(request.interactionToken(), request.correlationId(),
                            MSG_IN_PROGRESS, null, null, false)
                    : GatewayResponse.messageReply(request.messageId(), request.channelId(), request.correlationId(),
                            MSG_IN_PROGRESS, null, null, false));
            announced.set(true);

            var source = resolveSource(host);

            var probe = ytDlpService.probe(request, source);
            if (probe.hasSizeInfo() && probe.fileSizeBytes() > maxFileBytes) {
                double sizeMb  = probe.fileSizeBytes() / 1_048_576.0;
                double limitMb = maxFileBytes / 1_048_576.0;
                if (!ffmpegService.canFitInSize(probe.durationSeconds(), maxFileBytes)) {
                    var msg = "❌ O vídeo tem aproximadamente **%.1f MB** e não é possível comprimir para o limite de **%.0f MB** deste servidor."
                            .formatted(sizeMb, limitMb);
                    replied.set(true);
                    responseProducer.send(buildReply(request, isInteraction, announced.get(), msg, null, null, true));
                    job.markFailed("Pre-validation: %.1f MB > limit %.0f MB, uncompressible".formatted(sizeMb, limitMb));
                    jobRepository.save(job);
                    countFailure(host, request, "too_large");
                    return;
                }
                log.info("File (~{}MB) exceeds limit ({}MB) but compression is feasible — proceeding: correlationId={}",
                        (int) sizeMb, (int) limitMb, request.correlationId());
            } else if (!probe.hasSizeInfo() && probe.durationSeconds() > 0
                    && !ffmpegService.canFitInSize(probe.durationSeconds(), maxFileBytes)) {
                double limitMb   = maxFileBytes / 1_048_576.0;
                int durationMin  = (int) (probe.durationSeconds() / 60);
                var msg = "❌ O vídeo tem **%d min** de duração e não é possível comprimir para o limite de **%.0f MB** deste servidor."
                        .formatted(durationMin, limitMb);
                replied.set(true);
                responseProducer.send(buildReply(request, isInteraction, announced.get(), msg, null, null, true));
                job.markFailed("Pre-validation: %dmin duration uncompressible to %.0f MB".formatted(durationMin, limitMb));
                jobRepository.save(job);
                countFailure(host, request, "too_long");
                return;
            }

            job.markDownloading();
            jobRepository.save(job);

            var result = ytDlpService.execute(request, source, probe);

            Path fileToUpload = result.filePath();
            boolean image = ffmpegService.isImage(fileToUpload);
            if (ffmpegService.needsEncode(fileToUpload, request.qualidade())) {
                job.markEncoding();
                jobRepository.save(job);
                fileToUpload = ffmpegService.encode(fileToUpload, request.correlationId());
            }

            long actualSize = fileSize(fileToUpload);
            if (actualSize > maxFileBytes) {
                Path compressed = null;
                if (!image) {
                    log.info("File too large ({}MB), attempting size-targeted compression: correlationId={}",
                            actualSize / 1_048_576, request.correlationId());
                    job.markEncoding();
                    jobRepository.save(job);
                    try {
                        compressed = ffmpegService.encodeToSize(fileToUpload, request.correlationId(), maxFileBytes);
                    } catch (DownloadException e) {
                        if (timedOut.get()) throw e;
                        log.error("Compression failed: correlationId={}", request.correlationId(), e);
                    }
                    if (compressed != null) {
                        long compressedSize = fileSize(compressed);
                        if (compressedSize <= maxFileBytes) {
                            fileToUpload = compressed;
                            actualSize = compressedSize;
                        } else {
                            compressed = null;
                        }
                    }
                }
                if (compressed == null) {
                    double mb      = actualSize / 1_048_576.0;
                    double limitMb = maxFileBytes / 1_048_576.0;
                    var msg = "❌ O arquivo tem **%.1f MB** e não foi possível comprimir para o limite de **%.0f MB** deste servidor."
                            .formatted(mb, limitMb);
                    replied.set(true);
                    responseProducer.send(buildReply(request, isInteraction, announced.get(), msg, null, null, true));
                    job.markFailed("File too large after compression: %.1f MB".formatted(mb));
                    jobRepository.save(job);
                    countFailure(host, request, "too_large");
                    return;
                }
            }

            job.markUploading();
            jobRepository.save(job);
            var s3Key = s3UploadService.upload(fileToUpload, request.guildId(), request.correlationId());

            String attachmentUrl = "%s/%s/%s".formatted(
                    properties.s3().endpoint(),
                    properties.s3().bucket(),
                    s3Key);

            var attachment = new GatewayResponse.Attachment(
                    attachmentUrl,
                    uploadName(result.title(), fileToUpload),
                    result.title());

            replied.set(true);
            responseProducer.send(buildReply(request, isInteraction, announced.get(), result.title(), null, List.of(attachment), true));

            job.markDone(actualSize, s3Key);
            jobRepository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            meterRegistry.timer("downloader.job.duration", "source_host", host, "qualidade", request.qualidade())
                    .record(elapsed, TimeUnit.MILLISECONDS);
            meterRegistry.counter("downloader.job.completed", "source_host", host, "qualidade", request.qualidade()).increment();
            meterRegistry.summary("downloader.file.size", "source_host", host).record(actualSize);

        } catch (Exception e) {
            Thread.interrupted();
            String reason = failureReason(e, timedOut.get());
            if (timedOut.get()) {
                log.warn("Processing timed out: correlationId={}", request.correlationId());
            } else if (e instanceof ContentUnavailableException) {
                log.info("Content unavailable: correlationId={} detail={}", request.correlationId(), e.getMessage());
            } else if (e instanceof SourceBlockedException) {
                log.warn("Source blocked the download: correlationId={} host={} detail={}", request.correlationId(), host, e.getMessage());
            } else {
                log.error("Download failed: correlationId={}", request.correlationId(), e);
            }
            if (replied.compareAndSet(false, true)) {
                responseProducer.send(buildReply(request, isInteraction, announced.get(), failureMessage(e, timedOut.get()), null, null, true));
            }
            if (job != null) {
                try {
                    job.markFailed(timedOut.get() ? "Timeout" : truncate(e.getMessage()));
                    jobRepository.save(job);
                } catch (Exception persistError) {
                    log.error("Failed to persist job failure: correlationId={}", request.correlationId(), persistError);
                }
            }
            countFailure(host, request, reason);
        } finally {
            timeoutTask.cancel(false);
            Thread.interrupted();
            cleanup(workDir);
        }
    }

    private String failureMessage(Exception e, boolean timedOut) {
        if (timedOut) {
            return "⏱ Tempo limite de **%ds** atingido. Tente com um vídeo mais curto."
                    .formatted(properties.processingTimeoutSeconds());
        }
        if (e instanceof ContentUnavailableException) return MSG_UNAVAILABLE;
        if (e instanceof SourceBlockedException) return MSG_BLOCKED;
        if (e instanceof DownloadException) return MSG_GENERIC_FAILURE;
        return MSG_UNEXPECTED;
    }

    private static String failureReason(Exception e, boolean timedOut) {
        if (timedOut) return "Timeout";
        if (e instanceof ContentUnavailableException) return "ContentUnavailable";
        if (e instanceof SourceBlockedException) return "SourceBlocked";
        return e.getClass().getSimpleName();
    }

    private void countFailure(String host, DownloadRequest request, String reason) {
        meterRegistry.counter("downloader.job.failed", "source_host", host,
                "qualidade", request.qualidade(), "reason", reason).increment();
    }

    private boolean alreadyProcessed(String correlationId) {
        try {
            return jobRepository.existsByCorrelationId(correlationId);
        } catch (Exception e) {
            log.warn("Could not check for duplicate job: correlationId={}", correlationId, e);
            return false;
        }
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private static String uploadName(String title, Path file) {
        var name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        var ext = dot >= 0 ? name.substring(dot) : "";
        var base = title == null || title.isBlank() ? "download" : title;
        return base + ext;
    }

    private GatewayResponse buildReply(DownloadRequest request, boolean isInteraction, boolean announced,
                                       String content, List<GatewayResponse.Embed> embeds,
                                       List<GatewayResponse.Attachment> attachments, Boolean finished) {
        if (isInteraction) {
            return GatewayResponse.deferredReply(request.interactionToken(), request.correlationId(), content, embeds, attachments, finished);
        }
        if (!announced) {
            return GatewayResponse.messageReply(request.messageId(), request.channelId(), request.correlationId(), content, embeds, attachments, finished);
        }
        return GatewayResponse.updateMessage(request.messageId(), request.channelId(), request.correlationId(), content, embeds, attachments, finished);
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new DownloadException("Failed to read file size: " + path, e);
        }
    }

    private DownloadSource resolveSource(String host) {
        if (host == null || host.isBlank()) {
            return DownloadSource.generic("unknown");
        }
        long now = System.currentTimeMillis();
        var cached = sourceCache.get(host);
        if (cached == null || now - cached.loadedAt() > SOURCE_CACHE_TTL_MS) {
            cached = new CachedSource(sourceRepository.findByHostAndEnabledTrue(host), now);
            sourceCache.put(host, cached);
        }
        return cached.source().orElseGet(() -> {
            log.warn("No configured source for host '{}' — using generic yt-dlp fallback", host);
            return DownloadSource.generic(host);
        });
    }

    static String extractHost(String url) {
        try {
            var uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return "unknown";
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void cleanOrphanedTempDirs() {
        var tmp = Path.of(properties.tmpDir());
        if (!Files.exists(tmp)) return;
        try (var stream = Files.list(tmp)) {
            stream.filter(Files::isDirectory).forEach(this::cleanup);
            log.info("Cleaned orphaned temporary download directories from: {}", tmp);
        } catch (Exception e) {
            log.warn("Could not clean orphaned temp directories: {}", e.getMessage());
        }
    }

    private void cleanup(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir: {}", dir, e);
        }
    }
}
